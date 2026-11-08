package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.math.path.AxisConstraint;
import com.immersivecinematics.immersive_cinematics.script.CameraLaneComposite;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.object.ClipSampler;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.script.object.SampledProperty;
import com.immersivecinematics.immersive_cinematics.script.object.position.BaseSource;
import com.immersivecinematics.immersive_cinematics.script.object.position.EntityPoint;
import com.immersivecinematics.immersive_cinematics.script.object.position.FacingSource;
import com.immersivecinematics.immersive_cinematics.script.object.position.FacingSpec;
import com.immersivecinematics.immersive_cinematics.script.object.position.FreezeStore;
import com.immersivecinematics.immersive_cinematics.script.object.position.LookAtKind;
import com.immersivecinematics.immersive_cinematics.script.object.position.LookAtSpec;
import com.immersivecinematics.immersive_cinematics.script.object.position.Orient;
import com.immersivecinematics.immersive_cinematics.script.object.position.OrientationEvaluator;
import com.immersivecinematics.immersive_cinematics.script.object.position.OrientationResult;
import com.immersivecinematics.immersive_cinematics.script.object.position.OrientationSpec;
import com.immersivecinematics.immersive_cinematics.script.object.position.PathTangent;
import com.immersivecinematics.immersive_cinematics.script.object.position.PointFrame;
import com.immersivecinematics.immersive_cinematics.script.object.position.PointKinds;
import com.immersivecinematics.immersive_cinematics.script.object.position.PointOffset;
import com.immersivecinematics.immersive_cinematics.script.object.position.PointSourceSpec;
import com.immersivecinematics.immersive_cinematics.script.object.position.PositionEvaluator;
import com.immersivecinematics.immersive_cinematics.script.object.position.PositionMode;
import com.immersivecinematics.immersive_cinematics.script.object.position.PositionResult;
import com.immersivecinematics.immersive_cinematics.script.object.position.PositionSpace;
import com.immersivecinematics.immersive_cinematics.script.object.position.PositionSpec;
import com.immersivecinematics.immersive_cinematics.script.object.position.SampledPathTangent;
import com.immersivecinematics.immersive_cinematics.script.object.position.WorldContext;
import com.immersivecinematics.immersive_cinematics.script.path.PathBinding;
import com.immersivecinematics.immersive_cinematics.script.path.PathBindingResolver;
import com.immersivecinematics.immersive_cinematics.script.path.PathBindingResolver.PositionSampler;
import com.immersivecinematics.immersive_cinematics.script.path.PathBindingSource;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 新格式 CAMERA 片段的对象装配：一个 {@code objects} 片段在给定 {@code executionLocalTime} 求值出
 * 六参数 {@link CameraState} 与 lane 合成参数 {@link CameraLaneComposite}。
 *
 * <p>装配顺序（{@code track-camera.md} §3.3 / {@code runtime-evaluation.md} §3.5 1-4 步）：</p>
 * <ol>
 *   <li>{@link ClipRuntimeCache#sample(Clip, double)} 采样一次，所有属性同一 {@code t}；</li>
 *   <li>从槽位构造 {@link PositionSpec} / {@link FacingSpec} / {@link OrientationSpec}
 *       （{@code yaw_lock}/{@code pitch_lock} 用 {@link SampledClip#present(int)} 判「本时刻是否锁」）；</li>
 *   <li>{@link PositionEvaluator#evaluate} → 切线（{@code orient=tangent} 时用 {@link SampledPathTangent}
 *       中心差分，{@code position_space=base} 过本帧参考系变换成世界方向）→ {@link OrientationEvaluator#evaluate}；</li>
 *   <li>脚本级路径绑定（消费侧）：覆盖 {@code position.x/y/z} 中被选中的轴；未选轴保留采样值。
 *       切线按「选轴取路径导数、未选轴取采样导数」合成。解析 / 校验 / 索引留给 I-4。</li>
 *   <li>合成参数从同一 {@code SampledClip} / {@link ClipValueView} 读（{@code mask_shape} 是 value-only）。</li>
 * </ol>
 *
 * <p>参考系只经 {@link PositionResult#frame()} 传递，不写实例字段：同一实例的求值顺序不影响结果。
 * {@code capture=false} 时提交 {@link #lastWorldPos}（实体目标消失时的兜底与 {@code @e} 就近基准），
 * {@code capture=true} 不推进。本轮不含 morph / motion / breath；路径条目只做消费侧。</p>
 *
 * <p>仅客户端主线程使用；采样缓冲按片段身份复用（{@link ClipRuntimeCache}），稳态求值不新建集合。</p>
 */
public final class ObjectCameraEvaluator {

    /** 切线中心差分半宽（执行局部时间，秒）。 */
    private static final double TANGENT_EPSILON = 1.0E-3;

    /** 本类消费的对象路径（槽位在构造期解析一次）。 */
    private enum P {
        POS_X("position.x"), POS_Y("position.y"), POS_Z("position.z"),
        POS_MODE("position_mode"), POS_SPACE("position_space"), POS_ORIGIN("position_origin"),
        POS_ORIGIN_X("position_origin_x"), POS_ORIGIN_Y("position_origin_y"), POS_ORIGIN_Z("position_origin_z"),
        POS_ORIGIN_FREEZE("position_origin_freeze"),
        FACING_SOURCE("facing_source"), FACING_TARGET("facing_target"),
        FACING_TARGET_X("facing_target_x"), FACING_TARGET_Y("facing_target_y"),
        FACING_TARGET_Z("facing_target_z"),
        FACING_TARGET_OFFSET_X("facing_target_offset.x"),
        FACING_TARGET_OFFSET_Y("facing_target_offset.y"),
        FACING_TARGET_OFFSET_Z("facing_target_offset.z"),
        FACING_TARGET_OFFSET_SPACE("facing_target_offset.space"),
        FACING_TARGET_FREEZE("facing_target_freeze"), FACING_ENTITY("facing_entity"),

        YAW("yaw"), PITCH("pitch"), ROLL("roll"),
        YAW_LOCK("yaw_lock"), PITCH_LOCK("pitch_lock"),
        YAW_BASE("yaw_base"), PITCH_BASE("pitch_base"), YAW_BASE_SELECTOR("yaw_base_selector"),
        YAW_BASE_FROM("yaw_base_from"),
        YAW_BASE_FROM_X("yaw_base_from_x"), YAW_BASE_FROM_Y("yaw_base_from_y"),
        YAW_BASE_FROM_Z("yaw_base_from_z"),
        YAW_BASE_FROM_OFFSET_X("yaw_base_from_offset.x"),
        YAW_BASE_FROM_OFFSET_Y("yaw_base_from_offset.y"),
        YAW_BASE_FROM_OFFSET_Z("yaw_base_from_offset.z"),
        YAW_BASE_FROM_OFFSET_SPACE("yaw_base_from_offset.space"),
        YAW_BASE_FROM_FREEZE("yaw_base_from_freeze"),
        YAW_BASE_TO("yaw_base_to"),
        YAW_BASE_TO_X("yaw_base_to_x"), YAW_BASE_TO_Y("yaw_base_to_y"), YAW_BASE_TO_Z("yaw_base_to_z"),
        YAW_BASE_TO_OFFSET_X("yaw_base_to_offset.x"),
        YAW_BASE_TO_OFFSET_Y("yaw_base_to_offset.y"),
        YAW_BASE_TO_OFFSET_Z("yaw_base_to_offset.z"),
        YAW_BASE_TO_OFFSET_SPACE("yaw_base_to_offset.space"),
        YAW_BASE_TO_FREEZE("yaw_base_to_freeze"),
        LOOK_AT("look_at"), LOOK_AT_SELECTOR("look_at_selector"),
        LOOK_PART_X("look_at_part.x"), LOOK_PART_Y("look_at_part.y"), LOOK_PART_Z("look_at_part.z"),
        LOOK_TARGET_X("look_at_target_x"), LOOK_TARGET_Y("look_at_target_y"),
        LOOK_TARGET_Z("look_at_target_z"),
        LOOK_TARGET_STRUCTURE("look_at_target_structure"), LOOK_TARGET_BLOCK("look_at_target_block"),
        LOOK_OFFSET_X("look_at_offset.x"), LOOK_OFFSET_Y("look_at_offset.y"),
        LOOK_OFFSET_Z("look_at_offset.z"), LOOK_OFFSET_SPACE("look_at_offset.space"),
        LOOK_FREEZE("look_at_freeze"),
        YAW_OFFSET("yaw_offset"), PITCH_OFFSET("pitch_offset"),

        FOV("fov"), ZOOM("zoom"),
        OPACITY("opacity"),
        DEST_X("dest.x"), DEST_Y("dest.y"), DEST_W("dest.w"), DEST_H("dest.h"),
        SOURCE_X("source.x"), SOURCE_Y("source.y"), SOURCE_W("source.w"), SOURCE_H("source.h"),
        MASK_X("mask_x"), MASK_Y("mask_y"), MASK_WIDTH("mask_width"), MASK_HEIGHT("mask_height"),
        MASK_RADIUS("mask_radius");

        private final String path;

        P(String path) {
            this.path = path;
        }
    }

    private static final P[] PATHS = P.values();

    private final ClipRuntimeCache cache;
    private final WorldContext world;
    private final Vec3 originPos;
    private final ObjectSlotLayout layout;

    private final PositionEvaluator positions = new PositionEvaluator();
    private final OrientationEvaluator orientations = new OrientationEvaluator();
    private final FreezeStore freezes = new FreezeStore();

    /** 按片段身份缓存的切线（缓冲区按采样器布局分配一次）。 */
    private final Map<Clip, PathTangent> tangents = new IdentityHashMap<>();
    /** 脚本级路径绑定求值器（消费侧；缺省无绑定，I-4 通过 {@link #setPathBindingSource} 接线）。 */
    private PathBindingResolver pathBindings;
    /** 路径端点采样器：按片段身份缓存独立缓冲（不复用 {@link ClipRuntimeCache} 的主采样缓冲）。 */
    private final Map<Clip, PositionSampler> endpointSamplers = new IdentityHashMap<>();
    /** 片段 → 冻结桶键前缀（身份映射，稳定且不依赖 hashCode 碰撞）。 */
    private final Map<Clip, String> clipKeys = new IdentityHashMap<>();
    /** 片段 → 范围内关键帧时刻（可用性检查用；片段数据只读，故可缓存）。 */
    private final Map<Clip, double[]> usableTimes = new IdentityHashMap<>();

    private final int[] slots = new int[PATHS.length];
    private final float[] floatDefaults = new float[PATHS.length];
    private final boolean[] boolDefaults = new boolean[PATHS.length];
    private final String[] stringDefaults = new String[PATHS.length];

    /** 上一帧最终世界坐标（实体目标消失时停在原地、以及作为 {@code @e} 就近基准）。 */
    private Vec3 lastWorldPos;

    /** 本次 {@link #evaluate} 产出的合成参数；无位置结果时 = {@code null}。 */
    private CameraLaneComposite composite;

    /** 最近一次 {@link #evaluate} 的采样结果（供 lane 快照直接复用）；尚无求值 = {@code null}。 */
    private SampledClip lastSample;

    private int clipKeyCounter;

    /**
     * @param cache     本轨的采样缓存（{@code TrackTypeId.CAMERA}）
     * @param world     世界查询（选择器 / 方块 / 结构 / 玩家激活位置）
     * @param originPos 脚本激活位置（{@link #lastWorldPos} 初值）
     */
    public ObjectCameraEvaluator(ClipRuntimeCache cache, WorldContext world, Vec3 originPos) {
        this(cache, world, originPos, PathBindingSource.NONE);
    }

    /**
     * @param pathBindingSource 脚本级路径绑定来源（本轮只消费；{@code null} = 无绑定）
     */
    public ObjectCameraEvaluator(ClipRuntimeCache cache, WorldContext world, Vec3 originPos,
                                 PathBindingSource pathBindingSource) {
        this.cache = cache;
        this.world = world;
        this.originPos = originPos;
        this.lastWorldPos = originPos;
        this.layout = ObjectSlotLayout.of(TrackTypeId.CAMERA);
        this.pathBindings = new PathBindingResolver(
                pathBindingSource != null ? pathBindingSource : PathBindingSource.NONE);

        for (P path : PATHS) {
            int slot = layout.slotOf(path.path);
            slots[path.ordinal()] = slot;
            Object def = slot >= 0 ? layout.defs().get(slot).defaultValue() : null;
            floatDefaults[path.ordinal()] = def instanceof Number number ? number.floatValue() : 0f;
            boolDefaults[path.ordinal()] = def instanceof Boolean bool && bool;
            stringDefaults[path.ordinal()] = def instanceof String string ? string : "";
        }
        // look_at=coordinate 的 Y 缺省是 64（schema 无缺省值，这里显式承接）
        floatDefaults[P.LOOK_TARGET_Y.ordinal()] = 64f;
    }

    /**
     * I-4 接线：替换脚本级路径绑定来源（{@code null} = 不启用）。切换时丢弃已缓存的绑定与端点采样器。
     */
    public void setPathBindingSource(PathBindingSource source) {
        this.pathBindings = new PathBindingResolver(
                source != null ? source : PathBindingSource.NONE);
        this.endpointSamplers.clear();
    }

    /**
     * 求值一个片段在 {@code executionLocalTime} 的相机状态，并产出合成参数（{@link #composite()}）。
     *
     * @param capture  {@code true} = 不推进跨帧求值状态（{@link #lastWorldPos} 不变）
     * @return 六参数快照；位置点源 / 参考系不可解析 = {@code null}（片段本帧无画面）
     */
    public CameraState evaluate(Clip clip, double executionLocalTime, boolean capture) {
        SampledClip sample = cache.sample(clip, executionLocalTime);
        lastSample = sample;
        ClipValueView values = cache.values(clip);
        PathBinding binding = pathBindings.bindingFor(clip, executionLocalTime);
        PositionSpec positionSpec = positionSpec(sample, clip, binding, executionLocalTime);
        FacingSpec facing = facingSpec(sample);
        OrientationSpec orientationSpec = orientationSpec(sample, values);
        bind(values, sample, positionSpec);

        PositionResult position = positions.evaluate(positionSpec, facing, world, freezes, clipKey(clip));
        if (position == null) {
            composite = null;
            return null;
        }

        Vec3 tangent = null;
        if (orientationSpec.orient() == Orient.TANGENT) {
            Vec3 raw = tangent(clip, binding, executionLocalTime);
            tangent = toWorldDirection(raw, positionSpec, position);
        }
        OrientationResult orientation = orientations.evaluate(orientationSpec, position, tangent, world, freezes,
                clipKey(clip));

        composite = composite(sample, values);
        CameraState state = new CameraState(position.world(), orientation.yaw(), orientation.pitch(),
                orientation.roll(), f(sample, P.FOV), f(sample, P.ZOOM));
        if (!capture) {
            lastWorldPos = position.world();
        }
        return state;
    }

    /** 本次 {@link #evaluate} 产出的合成参数；{@code null} = 本帧无画面。 */
    public CameraLaneComposite composite() {
        return composite;
    }

    /** 最近一次 {@link #evaluate} 的采样结果；尚无求值 = {@code null}。 */
    public SampledClip sample() {
        return lastSample;
    }

    /**
     * 片段可用性（新格式）：只检查 {@code [0,duration]} 内的对象。候选时刻 = 各对象编译后（超界关键帧
     * 已被过滤）的关键帧时刻；静态对象用 {@code t=0} 检查一次。任一候选时刻位置点源 / 参考系不可解析，
     * 或启用的 {@code look_at} 目标不可解析 → 不可用。超界关键帧不参与，也不让片段不可用。
     */
    public boolean isClipUsable(Clip clip) {
        if (!clip.hasObjectProperties()) {
            return true;
        }
        for (double time : usableTimes(clip)) {
            SampledClip sample = cache.sample(clip, time);
            ClipValueView values = cache.values(clip);
            PathBinding binding = pathBindings.bindingFor(clip, time);
            PositionSpec positionSpec = positionSpec(sample, clip, binding, time);
            FacingSpec facing = facingSpec(sample);
            bind(values, sample, positionSpec);

            PositionResult position = positions.evaluate(positionSpec, facing, world, freezes, clipKey(clip));
            if (position == null) {
                return false;
            }
            OrientationSpec orientation = orientationSpec(sample, values);
            if (orientation.lookAt() != null && !lookAtResolvable(orientation.lookAt())) {
                return false;
            }
        }
        return true;
    }

    /** seek：丢弃冻结桶（冻结的是坐标值，seek = 硬定位，下一次求值重新捕获）。 */
    public void resetFreezes() {
        freezes.clear();
    }

    /** 停播 / 脚本替换 / seek：丢弃冻结桶、切线缓存、片段键与可用性时刻（采样缓存由调用方清）。 */
    public void resetRuntimeState() {
        freezes.clear();
        tangents.clear();
        pathBindings.clear();
        endpointSamplers.clear();
        clipKeys.clear();
        usableTimes.clear();
        clipKeyCounter = 0;
        composite = null;
        lastSample = null;
        lastWorldPos = originPos;
    }

    // ── 规格装配 ──

    private PositionSpec positionSpec(SampledClip sample, Clip clip, PathBinding binding,
                                      double executionLocalTime) {
        PositionMode mode = "absolute".equals(str(sample, P.POS_MODE)) ? PositionMode.ABSOLUTE
                : PositionMode.RELATIVE;
        PositionSpace space = "base".equals(str(sample, P.POS_SPACE)) ? PositionSpace.BASE
                : PositionSpace.WORLD;
        PointSourceSpec origin = mode == PositionMode.ABSOLUTE ? null
                : PointSourceSpec.positionOrigin(str(sample, P.POS_ORIGIN), space,
                        f(sample, P.POS_ORIGIN_X), f(sample, P.POS_ORIGIN_Y), f(sample, P.POS_ORIGIN_Z),
                        b(sample, P.POS_ORIGIN_FREEZE));
        float x = f(sample, P.POS_X);
        float y = f(sample, P.POS_Y);
        float z = f(sample, P.POS_Z);
        if (binding != null) {
            Vec3 path = pathBindings.position(binding, clip, executionLocalTime, endpointSampler(clip));
            AxisConstraint axes = binding.axes();
            if (axes.x()) {
                x = (float) path.x;
            }
            if (axes.y()) {
                y = (float) path.y;
            }
            if (axes.z()) {
                z = (float) path.z;
            }
        }
        return new PositionSpec(mode, space, origin, x, y, z);
    }

    private FacingSpec facingSpec(SampledClip sample) {
        FacingSource source = switch (str(sample, P.FACING_SOURCE)) {
            case "world" -> FacingSource.WORLD;
            case "entity" -> FacingSource.ENTITY;
            case "line" -> FacingSource.LINE;
            default -> FacingSource.AUTO;
        };
        PointSourceSpec target = PointSourceSpec.point(str(sample, P.FACING_TARGET),
                f(sample, P.FACING_TARGET_X), f(sample, P.FACING_TARGET_Y), f(sample, P.FACING_TARGET_Z),
                b(sample, P.FACING_TARGET_FREEZE), EntityPoint.CENTER,
                offset(sample, P.FACING_TARGET_OFFSET_X, P.FACING_TARGET_OFFSET_Y, P.FACING_TARGET_OFFSET_Z,
                        P.FACING_TARGET_OFFSET_SPACE));
        return new FacingSpec(source, target, str(sample, P.FACING_ENTITY));
    }

    private OrientationSpec orientationSpec(SampledClip sample, ClipValueView values) {
        LookAtSpec lookAt = lookAtSpec(sample);
        Orient orient = "tangent".equals(values.s("orient", "manual")) ? Orient.TANGENT : Orient.MANUAL;
        return OrientationSpec.builder()
                .yaw(f(sample, P.YAW))
                .pitch(f(sample, P.PITCH))
                .roll(f(sample, P.ROLL))
                .yawLock(nullable(sample, P.YAW_LOCK))
                .pitchLock(nullable(sample, P.PITCH_LOCK))
                .yawBase(baseSource(str(sample, P.YAW_BASE)))
                .pitchBase(baseSource(str(sample, P.PITCH_BASE)))
                .yawBaseSelector(str(sample, P.YAW_BASE_SELECTOR))
                .yawBaseFrom(pointSpec(sample, P.YAW_BASE_FROM, P.YAW_BASE_FROM_X, P.YAW_BASE_FROM_Y,
                        P.YAW_BASE_FROM_Z, P.YAW_BASE_FROM_OFFSET_X, P.YAW_BASE_FROM_OFFSET_Y,
                        P.YAW_BASE_FROM_OFFSET_Z, P.YAW_BASE_FROM_OFFSET_SPACE, P.YAW_BASE_FROM_FREEZE))
                .yawBaseTo(pointSpec(sample, P.YAW_BASE_TO, P.YAW_BASE_TO_X, P.YAW_BASE_TO_Y,
                        P.YAW_BASE_TO_Z, P.YAW_BASE_TO_OFFSET_X, P.YAW_BASE_TO_OFFSET_Y,
                        P.YAW_BASE_TO_OFFSET_Z, P.YAW_BASE_TO_OFFSET_SPACE, P.YAW_BASE_TO_FREEZE))
                .lookAt(lookAt)
                .orient(orient)
                .yawOffset(f(sample, P.YAW_OFFSET))
                .pitchOffset(f(sample, P.PITCH_OFFSET))
                .build();
    }

    /** 连线端点 / 方向源载荷的通用点源规格（实体取点 = 脚底中心）。 */
    private PointSourceSpec pointSpec(SampledClip sample, P value, P x, P y, P z,
                                      P offsetX, P offsetY, P offsetZ, P offsetSpace, P freeze) {
        return PointSourceSpec.point(str(sample, value), f(sample, x), f(sample, y), f(sample, z),
                b(sample, freeze), EntityPoint.FOOT,
                offset(sample, offsetX, offsetY, offsetZ, offsetSpace));
    }

    private LookAtSpec lookAtSpec(SampledClip sample) {
        String kind = str(sample, P.LOOK_AT);
        PointOffset offset = offset(sample, P.LOOK_OFFSET_X, P.LOOK_OFFSET_Y, P.LOOK_OFFSET_Z,
                P.LOOK_OFFSET_SPACE);
        boolean freeze = b(sample, P.LOOK_FREEZE);
        return switch (kind) {
            case "player" -> new LookAtSpec(LookAtKind.PLAYER, str(sample, P.LOOK_AT_SELECTOR),
                    f(sample, P.LOOK_PART_X), f(sample, P.LOOK_PART_Y), f(sample, P.LOOK_PART_Z),
                    f(sample, P.LOOK_TARGET_X), f(sample, P.LOOK_TARGET_Y), f(sample, P.LOOK_TARGET_Z),
                    str(sample, P.LOOK_TARGET_STRUCTURE), str(sample, P.LOOK_TARGET_BLOCK), offset, freeze);
            case "coordinate" -> new LookAtSpec(LookAtKind.COORDINATE, str(sample, P.LOOK_AT_SELECTOR),
                    f(sample, P.LOOK_PART_X), f(sample, P.LOOK_PART_Y), f(sample, P.LOOK_PART_Z),
                    f(sample, P.LOOK_TARGET_X), f(sample, P.LOOK_TARGET_Y), f(sample, P.LOOK_TARGET_Z),
                    str(sample, P.LOOK_TARGET_STRUCTURE), str(sample, P.LOOK_TARGET_BLOCK), offset, freeze);
            case "entity" -> new LookAtSpec(LookAtKind.ENTITY, str(sample, P.LOOK_AT_SELECTOR),
                    f(sample, P.LOOK_PART_X), f(sample, P.LOOK_PART_Y), f(sample, P.LOOK_PART_Z),
                    f(sample, P.LOOK_TARGET_X), f(sample, P.LOOK_TARGET_Y), f(sample, P.LOOK_TARGET_Z),
                    str(sample, P.LOOK_TARGET_STRUCTURE), str(sample, P.LOOK_TARGET_BLOCK), offset, freeze);
            case "block" -> new LookAtSpec(LookAtKind.BLOCK, str(sample, P.LOOK_AT_SELECTOR),
                    f(sample, P.LOOK_PART_X), f(sample, P.LOOK_PART_Y), f(sample, P.LOOK_PART_Z),
                    f(sample, P.LOOK_TARGET_X), f(sample, P.LOOK_TARGET_Y), f(sample, P.LOOK_TARGET_Z),
                    str(sample, P.LOOK_TARGET_STRUCTURE), str(sample, P.LOOK_TARGET_BLOCK), offset, freeze);
            default -> null;
        };
    }

    private CameraLaneComposite composite(SampledClip sample, ClipValueView values) {
        return new CameraLaneComposite(
                f(sample, P.OPACITY),
                f(sample, P.DEST_X), f(sample, P.DEST_Y), f(sample, P.DEST_W), f(sample, P.DEST_H),
                f(sample, P.SOURCE_X), f(sample, P.SOURCE_Y), f(sample, P.SOURCE_W), f(sample, P.SOURCE_H),
                values.s("mask_shape", "full"),
                f(sample, P.MASK_X), f(sample, P.MASK_Y), f(sample, P.MASK_WIDTH), f(sample, P.MASK_HEIGHT),
                f(sample, P.MASK_RADIUS));
    }

    // ── 求值辅助 ──

    /** 绑定世界上下文（只有 {@link ObjectWorldContext} 需要逐次求值的片段上下文）。 */
    private void bind(ClipValueView values, SampledClip sample, PositionSpec positionSpec) {
        if (world instanceof ObjectWorldContext objectWorld) {
            objectWorld.bind(values, sample, lastWorldPos, positionSpec.origin());
        }
    }

    /** {@code orient=tangent} 的切线：{@code position.x/y/z} 中心差分（表达空间导数）。 */
    private PathTangent tangent(Clip clip) {
        PathTangent tangent = tangents.get(clip);
        if (tangent == null) {
            tangent = SampledPathTangent.of(cache.sampler(clip), layout, TANGENT_EPSILON);
            tangents.put(clip, tangent);
        }
        return tangent;
    }

    /**
     * 有路径绑定时合成切线：选中的轴取路径导数，未选轴保留 {@link SampledPathTangent} 的采样导数。
     * 无绑定 / 采样导数不可用 = 原采样切线（或 {@code null}）。
     */
    private Vec3 tangent(Clip clip, PathBinding binding, double executionLocalTime) {
        Vec3 sampled = tangent(clip).direction(executionLocalTime);
        if (binding == null || sampled == null) {
            return sampled;
        }
        Vec3 path = pathBindings.tangent(binding, clip, executionLocalTime, endpointSampler(clip));
        AxisConstraint axes = binding.axes();
        return new Vec3(axes.x() ? path.x : sampled.x,
                axes.y() ? path.y : sampled.y,
                axes.z() ? path.z : sampled.z);
    }

    /** 路径端点采样器：独立缓冲，按片段身份缓存；未声明的轴 = schema 缺省值。 */
    private PositionSampler endpointSampler(Clip clip) {
        PositionSampler sampler = endpointSamplers.get(clip);
        if (sampler == null) {
            sampler = new EndpointSampler(cache.sampler(clip), floatDefaults[P.POS_X.ordinal()],
                    floatDefaults[P.POS_Y.ordinal()], floatDefaults[P.POS_Z.ordinal()]);
            endpointSamplers.put(clip, sampler);
        }
        return sampler;
    }

    /** 端点采样：每片段一块独立 {@link SampledClip} 缓冲（不能与主采样缓冲共用）。 */
    private static final class EndpointSampler implements PositionSampler {

        private final ClipSampler sampler;
        private final SampledClip buffer;
        private final int xSlot;
        private final int ySlot;
        private final int zSlot;
        private final float xDefault;
        private final float yDefault;
        private final float zDefault;

        private EndpointSampler(ClipSampler sampler, float xDefault, float yDefault, float zDefault) {
            this.sampler = sampler;
            this.buffer = sampler.newBuffer();
            this.xSlot = sampler.slotOf("position.x");
            this.ySlot = sampler.slotOf("position.y");
            this.zSlot = sampler.slotOf("position.z");
            this.xDefault = xDefault;
            this.yDefault = yDefault;
            this.zDefault = zDefault;
        }

        @Override
        public Vec3 positionAt(Clip clip, double localTime) {
            sampler.sample(localTime, buffer);
            return new Vec3(value(xSlot, xDefault), value(ySlot, yDefault), value(zSlot, zDefault));
        }

        private double value(int slot, float fallback) {
            return slot >= 0 && buffer.present(slot) ? buffer.f(slot) : fallback;
        }
    }

    /** 表达空间导数 → 世界方向：{@code base} 空间过本帧参考系，其余原样。 */
    private static Vec3 toWorldDirection(Vec3 raw, PositionSpec spec, PositionResult position) {
        if (raw == null || spec.mode() != PositionMode.RELATIVE || spec.space() != PositionSpace.BASE) {
            return raw;
        }
        PointFrame frame = position.frame();
        if (frame == null) {
            return raw;
        }
        return frame.fwd().scale(raw.x).add(frame.up().scale(raw.y)).add(frame.right().scale(raw.z));
    }

    /** {@code look_at} 目标是否可解析（只看来源点；偏移 / 冻结不改变可解析性）。 */
    private boolean lookAtResolvable(LookAtSpec spec) {
        return switch (spec.kind()) {
            case PLAYER -> world.playerActivation() != null;
            case COORDINATE -> spec.structure().isEmpty() || world.structureCenter(spec.structure()) != null;
            case ENTITY -> world.entityPoint("look_at", spec.selector(),
                    new EntityPoint(spec.partX(), spec.partY(), spec.partZ())) != null;
            case BLOCK -> {
                if (spec.block().isEmpty()) {
                    yield false;
                }
                PointKinds.BlockRef block = PointKinds.parseBlock(spec.block());
                yield block.blockId() != null && !block.blockId().isEmpty()
                        && world.blockCenter(block.blockId(), block.radius()) != null;
            }
        };
    }

    /** 范围内关键帧时刻（编译期已过滤超界帧）；无关键帧 = 单点 {@code t=0}。 */
    private double[] usableTimes(Clip clip) {
        double[] cached = usableTimes.get(clip);
        if (cached != null) {
            return cached;
        }
        float duration = clip.getDuration();
        boolean infinite = clip.isInfinite();
        TreeSet<Double> times = new TreeSet<>();
        for (SampledProperty property : clip.getObjects().asMap().values()) {
            SampledProperty.Compiled compiled = property.compile(duration, infinite);
            for (int i = 0; i < compiled.frameCount(); i++) {
                times.add((double) compiled.timeAt(i));
            }
        }
        if (times.isEmpty()) {
            times.add(0.0);
        }
        double[] result = new double[times.size()];
        int index = 0;
        for (double time : times) {
            result[index++] = time;
        }
        usableTimes.put(clip, result);
        return result;
    }

    /** 片段身份（冻结桶键的一段）：按对象身份分配稳定短键。 */
    private String clipKey(Clip clip) {
        String key = clipKeys.get(clip);
        if (key == null) {
            key = "cam" + (clipKeyCounter++);
            clipKeys.put(clip, key);
        }
        return key;
    }

    private static BaseSource baseSource(String value) {
        return switch (value) {
            case "entity" -> BaseSource.ENTITY;
            case "line" -> BaseSource.LINE;
            default -> BaseSource.WORLD;
        };
    }

    private PointOffset offset(SampledClip sample, P x, P y, P z, P space) {
        PositionSpace resolved = "base".equals(str(sample, space)) ? PositionSpace.BASE
                : PositionSpace.WORLD;
        return new PointOffset(new Vec3(f(sample, x), f(sample, y), f(sample, z)), resolved);
    }

    // ── 槽位读取（未声明 / 未采样 = schema 缺省） ──

    private float f(SampledClip sample, P path) {
        int slot = slots[path.ordinal()];
        return slot >= 0 && sample.present(slot) ? sample.f(slot) : floatDefaults[path.ordinal()];
    }

    private boolean b(SampledClip sample, P path) {
        int slot = slots[path.ordinal()];
        return slot >= 0 && sample.present(slot) ? sample.b(slot) : boolDefaults[path.ordinal()];
    }

    private String str(SampledClip sample, P path) {
        int slot = slots[path.ordinal()];
        String value = slot >= 0 && sample.present(slot) ? sample.s(slot) : null;
        return value != null ? value : stringDefaults[path.ordinal()];
    }

    /** {@code nullable_float}：本时刻没有值 = {@code null}（不锁）。 */
    private Float nullable(SampledClip sample, P path) {
        int slot = slots[path.ordinal()];
        return slot >= 0 && sample.present(slot) ? sample.f(slot) : null;
    }
}
