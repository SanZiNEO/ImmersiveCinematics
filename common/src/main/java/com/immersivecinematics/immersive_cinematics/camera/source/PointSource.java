package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 点源规格 = 来源 + 偏移：五形态来源 + 实体形态的取点 + 一层偏移（表达空间可选世界轴 / 基准坐标系）。
 *
 * 五形态（{@link Kind}，本类一处定义；位置基准 / 基准朝向目标 / 连线端点 / 注视点共用同一份解析）：
 * {@link Kind#WORLD} 玩家激活位置 / {@link Kind#COORDINATE} 固定坐标 / {@link Kind#SELECTOR} 实体选择器 /
 * {@link Kind#BLOCK} 就近搜索的方块中心 / {@link Kind#STRUCTURE} 结构中心。
 *
 * 坐标空间与单位：世界空间方块坐标；方块与结构取中心，实体取 {@link EntityPoint}（包围盒内每轴百分比）。
 * 叠加顺序 = 先取点（形态取点 / 实体百分比）后加偏移（{@link Offset#apply}）。
 * 数值契约：来源不可解析（实体未找到 / 结构未定位 / 附近没有该方块）= 求值返回 {@code null}，调用方按空片段兜底；
 * 字段缺省或空串 = 无点源（{@link #parse} 返回 {@code null}），与不可解析同语义。
 *
 * 字段形态（{@code <字段>} = 点源字段名，如 {@code yaw_base_from}）：{@code <字段>} = 形态字符串；
 * {@code <字段>_x/_y/_z} = coordinate 形态的配套坐标；{@code <字段>_offset} = 偏移对象。
 * 形态字符串：{@code "player"} 玩家激活位置；{@code "coordinate"} 固定坐标；{@code "block:id[:radius]"} 方块
 * （半径缺省 {@link PositionData#DEFAULT_BLOCK_RADIUS}）；{@code "@…"} / {@code "uuid:…"} 实体选择器；
 * 其余非空字符串 = 结构 id。
 */
public record PointSource(Kind kind, String selector, String structureId, String blockId, int blockRadius,
                          Vec3 coordinate, EntityPoint entityPoint, Offset offset) {

    /** 点源形态：{@code WORLD} / {@code COORDINATE} / {@code SELECTOR} / {@code BLOCK} / {@code STRUCTURE}。 */
    public enum Kind { WORLD, COORDINATE, SELECTOR, BLOCK, STRUCTURE }

    /** 偏移的表达空间：{@code WORLD} 世界轴（缺省）/ {@code BASE} 基准坐标系。 */
    public enum Space { WORLD, BASE }

    /**
     * 本帧基准坐标系（表达空间 {@link Space#BASE} 的参照系）：原点 + 三轴（单位向量，{@code fwd} / {@code right} / {@code up}）。
     * 由位置通道建立（见 {@link CameraKeyframeEvaluator}），每帧一套；{@code null} = 本帧没有基准坐标系。
     */
    public record Frame(Vec3 origin, Vec3 fwd, Vec3 right, Vec3 up) {}

    /**
     * 点源偏移：向量 + 表达空间。分量按表达空间对应：
     * {@link Space#WORLD} = {@code (dx, dy, dz)} 世界轴；{@link Space#BASE} = {@code (fwd, up, right)} 基准坐标系三轴。
     */
    public record Offset(Vec3 vector, Space space) {

        /** 无偏移。 */
        public static final Offset NONE = new Offset(Vec3.ZERO, Space.WORLD);

        /** 世界轴偏移（dx / dy / dz）。 */
        public static Offset world(float dx, float dy, float dz) {
            return new Offset(new Vec3(dx, dy, dz), Space.WORLD);
        }

        /** 基准坐标系偏移（fwd / up / right）。 */
        public static Offset base(float fwd, float up, float right) {
            return new Offset(new Vec3(fwd, up, right), Space.BASE);
        }

        /**
         * 把偏移施加到来源取点上。
         * {@link Space#BASE} 需要本帧基准坐标系：{@code frame} 为 {@code null}（本帧没有基准坐标系）= 偏移不生效。
         */
        public Vec3 apply(Vec3 point, Frame frame) {
            if (space == Space.WORLD) return point.add(vector);
            if (frame == null) return point;
            return point.add(frame.fwd().scale(vector.x))
                    .add(frame.right().scale(vector.z))
                    .add(frame.up().scale(vector.y));
        }
    }

    /**
     * 实体形态的取点：实体包围盒内的一个点，按每轴百分比定位。
     *
     * 单位与坐标空间：每轴百分比（0 = 该轴最小侧、100 = 最大侧），坐标空间 = 实体包围盒局部
     * （世界轴对齐，不随实体朝向旋转；横向 / 前后按包围盒宽、纵向按包围盒高，底面中心 = 实体位置）。
     * 数值契约：越界值钳制到 0 ~ 100，取点恒在包围盒内（不伸到体外）；NaN 按 50 处理。
     */
    public record EntityPoint(double xPct, double yPct, double zPct) {

        /** 脚底中心（50 / 0 / 50）：位置基准 / 连线端点 / 锚点。 */
        public static final EntityPoint FOOT = new EntityPoint(50, 0, 50);

        /** 包围盒中心（50 / 50 / 50）：注视点 / 基准朝向目标。 */
        public static final EntityPoint CENTER = new EntityPoint(50, 50, 50);

        public EntityPoint {
            xPct = clampPercent(xPct);
            yPct = clampPercent(yPct);
            zPct = clampPercent(zPct);
        }

        /** 钳制到 0 ~ 100；NaN = 50（中心）。 */
        private static double clampPercent(double v) {
            if (Double.isNaN(v)) return 50.0;
            if (v < 0.0) return 0.0;
            return v > 100.0 ? 100.0 : v;
        }
    }

    /** 附上偏移（不可变副本）：来源 + 偏移。 */
    public PointSource withOffset(Offset offset) {
        return new PointSource(kind, selector, structureId, blockId, blockRadius, coordinate, entityPoint, offset);
    }

    /** 玩家激活位置（{@code WORLD}）：脚本激活时玩家所在位置，整场不变。 */
    public static PointSource world() {
        return new PointSource(Kind.WORLD, null, null, null, 0, null, EntityPoint.FOOT, Offset.NONE);
    }

    /** 固定坐标（{@code COORDINATE}，世界空间方块坐标）。 */
    public static PointSource coordinate(double x, double y, double z) {
        return new PointSource(Kind.COORDINATE, null, null, null, 0, new Vec3(x, y, z), EntityPoint.FOOT,
                Offset.NONE);
    }

    /** 实体选择器（{@code SELECTOR}）：位置 = 实体渲染帧插值位置；取点由 {@code entityPoint} 决定。 */
    public static PointSource selector(String selector, EntityPoint entityPoint) {
        return new PointSource(Kind.SELECTOR, selector, null, null, 0, null, entityPoint, Offset.NONE);
    }

    /** 结构中心（{@code STRUCTURE}，结构 id）：结构 bounding box 中心，就近搜索。 */
    public static PointSource structure(String structureId) {
        return new PointSource(Kind.STRUCTURE, null, structureId, null, 0, null, EntityPoint.FOOT, Offset.NONE);
    }

    /** 就近搜索的方块中心（{@code BLOCK}，{@code block:id[:radius]} 口径）。 */
    public static PointSource block(String blockId, int radius) {
        return new PointSource(Kind.BLOCK, null, null, blockId, radius, null, EntityPoint.FOOT, Offset.NONE);
    }

    /**
     * 五形态判据：形态字符串 → {@link Kind}，{@link #parse} 与位置侧入口共用（判据本体在
     * {@link PositionData#originKindOf}，本方法只做词汇表映射）。
     *
     * @param value 形态字符串（关键帧字段值 / {@link PositionData} 携带的来源字符串）
     * @return 形态；无点源 = {@code null}
     */
    public static Kind kindOf(String value) {
        return kindOf(PositionData.originKindOf(value));
    }

    /** {@link PositionData} 来源常量 → {@link Kind}（唯一映射，两个入口共用）。 */
    private static Kind kindOf(int originType) {
        return switch (originType) {
            case PositionData.ORIGIN_COORDINATE -> Kind.COORDINATE;
            case PositionData.ORIGIN_BLOCK -> Kind.BLOCK;
            case PositionData.ORIGIN_SELECTOR -> Kind.SELECTOR;
            case PositionData.ORIGIN_STRUCTURE -> Kind.STRUCTURE;
            case PositionData.ORIGIN_PLAYER -> Kind.WORLD;
            default -> null;
        };
    }

    /**
     * 位置侧点源（{@link PositionData} 的 {@code facing_origin}）→ 规格：与 {@link #parse} 共用同一形态词汇表
     * （{@link #kindOf(int)}），本方法只做载荷装配。位置通道的偏移由 {@code position} 对象承载，不经本方法。
     *
     * @param pd          位置侧点源来源
     * @param entityPoint 实体形态的取点（其余形态不读）
     */
    public static PointSource of(PositionData pd, EntityPoint entityPoint) {
        return switch (kindOf(pd.getOriginType())) {
            case WORLD -> world();
            case COORDINATE -> coordinate(pd.getOriginX(), pd.getOriginY(), pd.getOriginZ());
            case BLOCK -> block(pd.getOriginBlockId(), pd.getOriginBlockRadius());
            case SELECTOR -> selector(pd.getOriginSelector(), entityPoint);
            case STRUCTURE -> structure(pd.getOriginStructure());
        };
    }

    /**
     * 解析点源字段：{@code value} = 形态字符串，配套坐标形态读 {@code kf} 的 {@code <field>_x/_y/_z}，
     * 偏移读 {@code <field>_offset}（{@link #parseOffset}）。判据见 {@link #kindOf}（一处定义，不复制）。
     *
     * @param kf          配套字段来源关键帧
     * @param field       点源字段名（{@code yaw_base_from} / {@code yaw_base_to} / {@code facing_target}）
     * @param value       形态字符串；{@code null} / 空串 = 无点源（由调用点决定取值来源：关键帧字段 / 位置侧解析结果）
     * @param entityPoint 实体形态的取点（其余形态不读）
     * @return 点源规格（含偏移）；无点源 = {@code null}
     */
    public static PointSource parse(Keyframe kf, String field, String value, EntityPoint entityPoint) {
        Kind kind = kindOf(value);
        if (kind == null) return null;
        PointSource source = switch (kind) {
            case WORLD -> world();
            case COORDINATE -> coordinate(kf.getFloat(field + "_x", 0f), kf.getFloat(field + "_y", 0f),
                    kf.getFloat(field + "_z", 0f));
            case SELECTOR -> selector(value, entityPoint);
            case BLOCK -> blockFromString(value);
            case STRUCTURE -> structure(value);
        };
        return source.withOffset(parseOffset(kf, field));
    }

    /** 方块形态字符串（{@code block:id[:radius]}）→ 方块点源。 */
    public static PointSource blockFromString(String value) {
        String[] parsed = PositionData.parseBlockOriginString(value);
        return block(parsed[0], Integer.parseInt(parsed[1]));
    }

    /**
     * 解析点源偏移字段 {@code <field>_offset}：对象形态，两类分量二选一（{@link Offset}）——
     * {@code dx/dy/dz} = 世界轴（缺省表达空间）；{@code fwd/up/right} = 基准坐标系；缺省分量 0。
     * 两类混写按世界轴处理（校验器拦下）。字段缺省 / 非对象 / 空对象 = 无偏移。
     *
     * @param kf    字段来源关键帧
     * @param field 点源字段名（{@code look_at} 的偏移字段名即 {@code look_at_offset}）
     */
    public static Offset parseOffset(Keyframe kf, String field) {
        if (!(kf.getObject(field + "_offset") instanceof Map<?, ?> m)) return Offset.NONE;
        boolean worldAxis = m.containsKey("dx") || m.containsKey("dy") || m.containsKey("dz");
        if (worldAxis) {
            return new Offset(new Vec3(num(m.get("dx")), num(m.get("dy")), num(m.get("dz"))), Space.WORLD);
        }
        if (m.containsKey("fwd") || m.containsKey("up") || m.containsKey("right")) {
            return new Offset(new Vec3(num(m.get("fwd")), num(m.get("up")), num(m.get("right"))), Space.BASE);
        }
        return Offset.NONE;
    }

    /** 偏移分量：非数字 = 0。 */
    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
