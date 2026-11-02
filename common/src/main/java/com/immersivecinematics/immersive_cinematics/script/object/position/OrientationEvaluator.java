package com.immersivecinematics.immersive_cinematics.script.object.position;

import com.immersivecinematics.immersive_cinematics.camera.source.PointSource;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * 朝向求值：注视目标 > 切线 > 手动，纯通用层（resolver 通过 {@link WorldContext} 注入）。
 *
 * <p>优先级（{@code track-camera.md} §3.3）：</p>
 * <ol>
 *   <li>{@code look_at != null} 且目标可解析：{@code yaw}/{@code pitch} 由当前位置到目标点的方向决定
 *       （含目标偏移与冻结；实体目标用 {@code look_at_part} 百分比，缺省 50/50/50；方块目标用
 *       {@code block:<id>[:r]}）；{@code roll} 取规格；{@code yaw}/{@code pitch} 偏移与方向锁都不参与。</li>
 *   <li>{@code orient=tangent} 且有切线（切线覆盖 {@code look_at=null} 与"目标不可解析回退"两种情况）：
 *       {@code yaw = atan2(dz,dx) - 90}、{@code pitch = -atan2(dy,horizontal)}（水平 ≈ 0 时
 *       {@code yaw=0}、{@code pitch=±90}），再叠加 {@code yaw_offset}/{@code pitch_offset}。</li>
 *   <li>手动：每轴基准（{@code world} / {@code entity} / {@code line}）→ 锁值替换 → 加 {@code yaw}/{@code pitch}；
 *       {@code roll} 直接。</li>
 * </ol>
 *
 * <p>退化与缺失一律按"不可解析"处理，不抛异常：{@code look_at} 目标为空 → 回退切线 / 手动；
 * {@code line} 端点不可解析或水平分量小于 {@value #EPSILON} → 基准 0（= world）；
 * 实体基准未解析（NaN）→ 0。切线偏移只在切线支读取，手动支忽略。</p>
 */
public final class OrientationEvaluator {

    /** 注视目标的冻结桶对象路径。 */
    static final String PATH_LOOK_AT = "look_at";

    /** 基准连线起点的冻结桶对象路径。 */
    static final String PATH_YAW_BASE_FROM = "yaw_base_from";

    /** 基准连线终点的冻结桶对象路径。 */
    static final String PATH_YAW_BASE_TO = "yaw_base_to";

    /** 方向 / 水平分量阈值。 */
    static final double EPSILON = 1.0E-6;

    private static final double[] ZERO_ANGLES = {0.0, 0.0};

    /**
     * 求值一个朝向规格。
     *
     * @param spec       朝向规格（非空）
     * @param position   位置结果（{@code null} = 无当前位置，{@code look_at} 不可算）
     * @param tangentDir 路径切线方向（采样表达空间，未归一化）；{@code null} = 无切线
     * @param ctx        世界查询
     * @param freezes    冻结桶
     * @param clipKey    片段身份
     * @return 世界角（度）
     */
    public OrientationResult evaluate(OrientationSpec spec, PositionResult position, Vec3 tangentDir,
                                      WorldContext ctx, FreezeStore freezes, String clipKey) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(freezes, "freezes");

        Vec3 world = position == null ? null : position.world();
        PointSource.Frame frame = position == null ? null : position.frame();

        if (spec.lookAt() != null && world != null) {
            Vec3 target = resolveLookAt(spec.lookAt(), frame, ctx, freezes, clipKey);
            if (target != null) {
                float[] yawPitch = yawPitch(world, target);
                return new OrientationResult(yawPitch[0], yawPitch[1], spec.roll());
            }
        }

        if (spec.orient() == Orient.TANGENT && tangentDir != null
                && tangentDir.lengthSqr() >= EPSILON * EPSILON) {
            float[] yawPitch = tangentYawPitch(tangentDir);
            return new OrientationResult(yawPitch[0] + (float) spec.yawOffset(),
                    yawPitch[1] + (float) spec.pitchOffset(), spec.roll());
        }

        return manual(spec, ctx, freezes, clipKey);
    }

    /** 手动朝向：基准 / 锁 + 偏移。 */
    private static OrientationResult manual(OrientationSpec spec, WorldContext ctx, FreezeStore freezes,
                                            String clipKey) {
        double[] line = spec.yawBase() == BaseSource.LINE || spec.pitchBase() == BaseSource.LINE
                ? lineAngles(spec, ctx, freezes, clipKey) : null;

        double baseYaw = switch (spec.yawBase()) {
            case WORLD -> 0.0;
            case ENTITY -> finiteOrZero(ctx.entityBodyYaw(spec.yawBaseSelector()));
            case LINE -> line[0];
        };
        double basePitch = switch (spec.pitchBase()) {
            case WORLD -> 0.0;
            case ENTITY -> finiteOrZero(ctx.entityLookPitch(spec.yawBaseSelector()));
            case LINE -> line[1];
        };
        double yawBase = spec.yawLock() != null ? spec.yawLock() : baseYaw;
        double pitchBase = spec.pitchLock() != null ? spec.pitchLock() : basePitch;
        return new OrientationResult((float) (yawBase + spec.yaw()), (float) (pitchBase + spec.pitch()), spec.roll());
    }

    /**
     * 基准连线角度：{@code yaw_base_from} → {@code yaw_base_to} 的连线方向（水平角 / 垂直角）。
     * 端点不可解析或水平分量退化 → {@code {0, 0}}（回退 world 基准，不抛）。
     */
    private static double[] lineAngles(OrientationSpec spec, WorldContext ctx, FreezeStore freezes,
                                       String clipKey) {
        Vec3 from = PositionEvaluator.resolvePoint(spec.yawBaseFrom(), null, ctx, freezes, clipKey,
                PATH_YAW_BASE_FROM);
        Vec3 to = PositionEvaluator.resolvePoint(spec.yawBaseTo(), null, ctx, freezes, clipKey, PATH_YAW_BASE_TO);
        if (from == null || to == null) {
            return ZERO_ANGLES;
        }
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < EPSILON) {
            return ZERO_ANGLES;
        }
        return new double[]{Math.toDegrees(Math.atan2(dz, dx)) - 90.0,
                -Math.toDegrees(Math.atan2(dy, horizontal))};
    }

    /** 注视目标取点（含偏移 / 冻结）；不可解析 = {@code null}。 */
    private static Vec3 resolveLookAt(LookAtSpec spec, PointSource.Frame frame, WorldContext ctx,
                                      FreezeStore freezes, String clipKey) {
        Vec3 point = switch (spec.kind()) {
            case PLAYER -> ctx.playerActivation();
            case COORDINATE -> spec.structure().isEmpty()
                    ? new Vec3(spec.targetX(), spec.targetY(), spec.targetZ())
                    : ctx.structureCenter(spec.structure());
            case ENTITY -> ctx.entityPoint(spec.selector(),
                    new PointSource.EntityPoint(spec.partX(), spec.partY(), spec.partZ()));
            case BLOCK -> blockPoint(spec.block(), ctx);
        };
        if (point == null) {
            return null;
        }
        PointSource.Offset offset = spec.offset();
        if (offset != null) {
            point = offset.apply(point, frame);
        }
        if (spec.freeze()) {
            return freezes.getOrCapture(FreezeStore.key(clipKey, PATH_LOOK_AT, spec.signature()), true, point);
        }
        return point;
    }

    /** 方块点源（{@code block:<id>[:radius]}；缺前缀时按裸 id + 缺省半径）→ 方块中心。 */
    private static Vec3 blockPoint(String value, WorldContext ctx) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        PointSource block = PointSource.blockFromString(value.startsWith("block:") ? value : "block:" + value);
        return ctx.blockCenter(block.blockId(), block.blockRadius());
    }

    /** 朝向目标方向 → {@code [yaw, pitch]}（度）；水平分量退化时 yaw 沿用 atan2(0,0) 结果、pitch = ±90。 */
    private static float[] yawPitch(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = horizontal < EPSILON
                ? (dy >= 0.0 ? -90f : 90f)
                : (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    /** 切线方向 → {@code [yaw, pitch]}（度）；水平分量退化时 yaw = 0、pitch = ±90。 */
    private static float[] tangentYawPitch(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontal < EPSILON) {
            return new float[]{0f, direction.y >= 0.0 ? -90f : 90f};
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        return new float[]{yaw, pitch};
    }

    /** 未解析（NaN / 无穷）= 0（world 基准），与旧链路一致。 */
    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
