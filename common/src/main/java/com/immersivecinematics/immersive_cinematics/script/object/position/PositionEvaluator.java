package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * 位置求值：点源 + 偏移 → 世界坐标与本帧参考系（纯通用层，resolver 通过 {@link WorldContext} 注入）。
 *
 * <p>语义（{@code track-camera.md} §3.2）：</p>
 * <ul>
 *   <li>{@link PositionMode#ABSOLUTE}：{@code world = (x, y, z)}，{@code frame = null}；
 *       {@code absolute} + {@code base} / 非空点源由 validator 拒绝，本层不做修复。</li>
 *   <li>{@link PositionMode#RELATIVE}：先解析原点（世界激活位置 / 坐标 / 实体选择器 / 方块 / 结构），
 *       不可解析 = {@code null}；{@link PointSpace#WORLD} 时三轴是世界轴偏移、{@code frame = null}；
 *       {@link PointSpace#BASE} 时先按原点建参考系（{@link #frame}），再把三轴当
 *       {@code (fwd, up, right)} 施加，参考系不可建 = {@code null}。</li>
 *   <li>冻结：{@code position_origin_freeze=true} 时把首次解析出的"含偏移世界点"写入
 *       {@link FreezeStore}（键 = 片段 + {@code position_origin} + 来源签名），之后复用；签名变化 = 新桶。
 *       参考系原点用冻结后的世界点，方向源仍实时。</li>
 * </ul>
 *
 * <p>方向源（{@link #frame}）：{@code auto} 按载荷与原点回落；{@code world} = 原版坐标轴
 * （{@code fwd=+Z}、{@code right=-X}、{@code up=+Y}）；{@code entity} = 实体身体水平朝向；
 * {@code line} = 原点 → 目标点的水平方向。上轴恒为世界竖直，前轴 / 右轴取水平面内分量。</p>
 *
 * <p>本类无状态；同一实例可复用于所有片段。解析失败返回 {@code null}（调用方按空片段兜底），
 * 不引入替代点；只有编程错误（{@code auto} 双载荷、显式 {@code entity} 无实体来源、
 * 显式 {@code line} 无目标）才抛 {@link IllegalArgumentException}。</p>
 */
public final class PositionEvaluator {

    /** 位置点源的冻结桶对象路径。 */
    static final String PATH_POSITION_ORIGIN = "position_origin";

    /** 方向源 {@code line} 目标的冻结桶对象路径。 */
    static final String PATH_FACING_TARGET = "facing_target";

    /** 连线水平分量阈值：小于它 = 水平角未定义（零长度 / 纯垂直线）。 */
    static final double LINE_HORIZONTAL_EPSILON = 1.0E-6;

    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 WORLD_FWD = new Vec3(0.0, 0.0, 1.0);
    private static final Vec3 WORLD_RIGHT = new Vec3(-1.0, 0.0, 0.0);

    /**
     * 求值一个位置规格。
     *
     * @param spec    位置规格（非空）
     * @param facing  方向源（{@code null} = {@code auto}）
     * @param ctx     世界查询
     * @param freezes 冻结桶（按播放实例 / 执行侧持有）
     * @param clipKey 片段身份（冻结桶键的一段；{@code null} 按空串）
     * @return 世界坐标 + 参考系；点源 / 参考系不可解析 = {@code null}
     */
    public PositionResult evaluate(PositionSpec spec, FacingSpec facing, WorldContext ctx, FreezeStore freezes,
                                   String clipKey) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(freezes, "freezes");

        if (spec.mode() == PositionMode.ABSOLUTE) {
            return new PositionResult(new Vec3(spec.x(), spec.y(), spec.z()), null);
        }

        PointSourceSpec originSpec = spec.origin();
        Vec3 origin = resolveSource(originSpec, ctx, PATH_POSITION_ORIGIN);
        if (origin == null) {
            return null;
        }

        if (spec.space() != PointSpace.BASE) {
            Vec3 world = origin.add(spec.x(), spec.y(), spec.z());
            world = freeze(originSpec, world, freezes, clipKey, PATH_POSITION_ORIGIN);
            return new PositionResult(world, null);
        }

        PointFrame frame = frame(origin, facing, originSpec, ctx, freezes, clipKey);
        if (frame == null) {
            return null;
        }
        Vec3 world = origin.add(frame.fwd().scale(spec.x()))
                .add(frame.up().scale(spec.y()))
                .add(frame.right().scale(spec.z()));
        if (originSpec != null && originSpec.freeze()) {
            world = freezes.getOrCapture(FreezeStore.key(clipKey, PATH_POSITION_ORIGIN, originSpec.signature()),
                    true, world);
            frame = new PointFrame(world, frame.fwd(), frame.right(), frame.up());
        }
        return new PositionResult(world, frame);
    }

    /**
     * 建本帧参考系（{@code position_space=base} 的基准系）：原点 + 三轴。
     *
     * <p>{@link FacingSource#AUTO}：{@code facing_target} 与 {@code facing_entity} 同时非空 → 抛
     * {@link IllegalArgumentException}（必须显式选一个）；有目标 → {@code line}；有实体 → {@code entity}；
     * 都没有 → 原点来源是实体选择器 / 玩家激活位置时 {@code entity}，坐标 / 方块 / 结构时 {@code world}。</p>
     *
     * <p>{@link FacingSource#ENTITY}：选择器取 {@code facing_entity}，缺省回落原点选择器（原点非实体 →
     * 抛 {@link IllegalArgumentException}）；身体朝向未解析（NaN）= {@code null}。
     * {@link FacingSource#LINE}：目标必填，取点用 {@link #resolvePoint}（含目标偏移与冻结，基准系偏移按
     * 无参考系处理）；水平分量小于 {@value #LINE_HORIZONTAL_EPSILON} = {@code null}。</p>
     *
     * @param origin     原点（点源解析点）
     * @param facing     方向源；{@code null} = {@code auto}
     * @param originSpec 原点规格（{@code auto} 回落与 {@code entity} 选择器缺省用）
     * @param ctx        世界查询
     * @param freezes    冻结桶
     * @param clipKey    片段身份
     * @return 参考系；原点为空 / 方向源不可用 = {@code null}
     */
    public static PointFrame frame(Vec3 origin, FacingSpec facing, PointSourceSpec originSpec,
                                   WorldContext ctx, FreezeStore freezes, String clipKey) {
        if (origin == null) {
            return null;
        }
        FacingSpec source = facing == null ? FacingSpec.auto() : facing;
        FacingSource resolved = source.source() == null ? FacingSource.AUTO : source.source();
        if (resolved == FacingSource.AUTO) {
            resolved = autoSource(source, originSpec);
        }
        return switch (resolved) {
            case WORLD -> new PointFrame(origin, WORLD_FWD, WORLD_RIGHT, UP);
            case ENTITY -> entityFrame(origin, source, originSpec, ctx);
            case LINE -> lineFrame(origin, source, ctx, freezes, clipKey);
            case AUTO -> throw new IllegalStateException("方向源未解析");
        };
    }

    /** {@code auto} 回落：载荷优先，其次按原点来源。 */
    private static FacingSource autoSource(FacingSpec facing, PointSourceSpec originSpec) {
        boolean hasTarget = facing.target() != null;
        boolean hasEntity = facing.entity() != null && !facing.entity().isEmpty();
        if (hasTarget && hasEntity) {
            throw new IllegalArgumentException("facing_source=auto 时 facing_target 与 facing_entity 不能同时非空");
        }
        if (hasTarget) {
            return FacingSource.LINE;
        }
        if (hasEntity) {
            return FacingSource.ENTITY;
        }
        if (originSpec == null || originSpec.kind() == null) {
            return FacingSource.WORLD;
        }
        return switch (originSpec.kind()) {
            case SELECTOR, WORLD -> FacingSource.ENTITY;
            case COORDINATE, BLOCK, STRUCTURE -> FacingSource.WORLD;
        };
    }

    /** 实体方向：只取身体水平朝向；选择器缺省回落原点选择器。 */
    private static PointFrame entityFrame(Vec3 origin, FacingSpec facing, PointSourceSpec originSpec,
                                          WorldContext ctx) {
        String selector = facing.entity();
        if (selector == null || selector.isEmpty()) {
            if (originSpec == null || originSpec.kind() != PointKind.SELECTOR) {
                throw new IllegalArgumentException("facing_source=entity 需要 facing_entity 或实体选择器原点");
            }
            selector = originSpec.selector();
        }
        if (selector == null || selector.isEmpty()) {
            return null;
        }
        double yaw = ctx.entityBodyYaw("facing_entity", selector);
        if (Double.isNaN(yaw)) {
            return null;
        }
        double radians = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
        return new PointFrame(origin, forward, forward.cross(UP), UP);
    }

    /** 连线方向：原点 → 目标点的水平方向；退化 = {@code null}。 */
    private static PointFrame lineFrame(Vec3 origin, FacingSpec facing, WorldContext ctx,
                                        FreezeStore freezes, String clipKey) {
        PointSourceSpec target = facing.target();
        if (target == null) {
            throw new IllegalArgumentException("facing_source=line 需要 facing_target");
        }
        Vec3 point = resolvePoint(target, null, ctx, freezes, clipKey, PATH_FACING_TARGET);
        if (point == null) {
            return null;
        }
        double dx = point.x - origin.x;
        double dz = point.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < LINE_HORIZONTAL_EPSILON) {
            return null;
        }
        Vec3 forward = new Vec3(dx / horizontal, 0.0, dz / horizontal);
        return new PointFrame(origin, forward, forward.cross(UP), UP);
    }

    /**
     * 点源取点（不含偏移 / 冻结）：只做形态分派，未解析 = {@code null}。
     *
     * <p>位置原点用它（位置偏移由 {@code position.x/y/z} 承担，冻结的是含偏移世界点，故此处不冻结）；
     * 连线端点 / 注视目标用 {@link #resolvePoint}。</p>
     *
     * @param callpoint 实体形态的选择器调用点（策略 / 锚点 / 择一 / 锁键）；{@code null} = 只用通用字段
     */
    static Vec3 resolveSource(PointSourceSpec source, WorldContext ctx, String callpoint) {
        if (source == null || source.kind() == null) {
            return null;
        }
        return switch (source.kind()) {
            case WORLD -> ctx.playerActivation();
            case COORDINATE -> source.coordinate();
            case SELECTOR -> source.selector() == null || source.selector().isEmpty()
                    ? null : ctx.entityPoint(callpoint, source.selector(), source.entityPoint());
            case BLOCK -> source.blockId() == null || source.blockId().isEmpty()
                    ? null : ctx.blockCenter(source.blockId(), source.blockRadius());
            case STRUCTURE -> source.structureId() == null || source.structureId().isEmpty()
                    ? null : ctx.structureCenter(source.structureId());
        };
    }

    /**
     * 点源取点 + 一层偏移 + 可选冻结（连线端点 / 注视目标 / 基准连线端点共用）。
     *
     * @param source       点源规格
     * @param frameForBase 基准系偏移用的参考系；{@code null} = 基准系偏移不生效（世界轴偏移照常）
     * @param ctx          世界查询
     * @param freezes      冻结桶
     * @param clipKey      片段身份
     * @param path         对象路径（冻结桶键的一段，同时作为选择器调用点）
     * @return 含偏移（含冻结）的世界点；不可解析 = {@code null}
     */
    static Vec3 resolvePoint(PointSourceSpec source, PointFrame frameForBase, WorldContext ctx,
                             FreezeStore freezes, String clipKey, String path) {
        Vec3 point = resolveSource(source, ctx, path);
        if (point == null) {
            return null;
        }
        PointOffset offset = source.offset();
        if (offset != null) {
            point = offset.apply(point, frameForBase);
        }
        return freeze(source, point, freezes, clipKey, path);
    }

    /** 按来源冻结开关写桶 / 复用。 */
    private static Vec3 freeze(PointSourceSpec source, Vec3 point, FreezeStore freezes, String clipKey,
                               String path) {
        if (source == null || !source.freeze()) {
            return point;
        }
        return freezes.getOrCapture(FreezeStore.key(clipKey, path, source.signature()), true, point);
    }
}
