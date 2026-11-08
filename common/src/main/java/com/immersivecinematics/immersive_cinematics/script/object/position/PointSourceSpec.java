package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

/**
 * 点源规格：来源形态 + 载荷 + 取点 + 一层偏移 + 冻结开关。
 *
 * <p>形态词汇表见 {@link PointKind}（判据 = {@link PointKinds#kindOf(String)}，一处定义）：
 * {@code player} / {@code coordinate} / {@code block:id[:radius]} / {@code @…} · {@code uuid:…} 选择器 /
 * 其余非空 = 结构 id。时态只有 {@code freeze} 布尔：{@code true} = 首次解析成功后固定坐标值，
 * {@code false} = 实时。</p>
 *
 * <p>偏移表达空间见 {@link PointOffset}：世界轴 / 基准坐标系；基准系偏移需要调用方给
 * 本帧参考系，{@code null} 表示本帧没有参考系（偏移不生效）。</p>
 *
 * <p>冻结键：{@link #signature()} 在构造期由全部字段派生（分隔符 {@code U+001F}），运行期只读、
 * 不拼字符串；来源值变化 = 签名变化 = 新桶，避免"第一次捕获后整个片段冻结"的歧义。
 * 最后一维是派生缓存：即使调用方传入也会被重算，保证签名恒等于字段的函数。</p>
 *
 * <p>冻结的粒度由求值层决定（见 {@link PositionEvaluator}）：位置点源冻结的是"含位置偏移的世界点"，
 * 不是来源点；连线端点 / 注视点冻结的是各自的取点（含各自偏移）。</p>
 */
public record PointSourceSpec(PointKind kind, String selector, String structureId, String blockId,
                              int blockRadius, Vec3 coordinate, EntityPoint entityPoint,
                              PointOffset offset, boolean freeze, String signature) {

    /** 签名分隔符：单元分隔符，正常选择器 / 结构 id / 方块 id 不会出现。 */
    private static final char SEP = '\u001f';

    public PointSourceSpec {
        entityPoint = entityPoint == null ? EntityPoint.FOOT : entityPoint;
        offset = offset == null ? PointOffset.NONE : offset;
        signature = derive(kind, selector, structureId, blockId, blockRadius, coordinate, entityPoint, offset, freeze);
    }

    /** 常规构造：签名由其余字段在构造期派生，不接受调用方传入。 */
    public PointSourceSpec(PointKind kind, String selector, String structureId, String blockId,
                           int blockRadius, Vec3 coordinate, EntityPoint entityPoint,
                           PointOffset offset, boolean freeze) {
        this(kind, selector, structureId, blockId, blockRadius, coordinate, entityPoint, offset, freeze, null);
    }

    /**
     * 位置点源（{@code position_origin}）→ 规格：空来源按表达空间补缺省（不写 = 不显式指定）。
     *
     * <p>空来源 + {@link PointSpace#WORLD} = 玩家激活位置（{@link PointKind#WORLD}，静态）；
     * 空来源 + {@link PointSpace#BASE} = 隐式玩家实体 {@code @p}（{@link PointKind#SELECTOR}，
     * 仍是实体选择器，{@code freeze} 按参数生效；缺省 {@code false} 即不冻结）。
     * 非空来源按 {@link PointKinds#kindOf} 分派：{@code coordinate} 读 {@code x/y/z}；
     * {@code block:id[:radius]} 用 {@link PointKinds#parseBlock}；选择器 / 结构 id 原样携带。</p>
     *
     * @param origin 形态字符串；{@code null} / 空串 = 不显式指定
     * @param space  偏移表达空间（决定空来源的缺省点源）
     * @param x      {@code coordinate} 形态的 X
     * @param y      {@code coordinate} 形态的 Y
     * @param z      {@code coordinate} 形态的 Z
     * @param freeze 冻结开关（只对实体选择器形态有意义，静态来源写了等价于无效果）
     * @return 点源规格；不会为 {@code null}
     */
    public static PointSourceSpec positionOrigin(String origin, PointSpace space, double x, double y, double z,
                                                 boolean freeze) {
        PointSpace resolvedSpace = space == null ? PointSpace.WORLD : space;
        if (origin == null || origin.isEmpty()) {
            PointKind kind = resolvedSpace == PointSpace.BASE
                    ? PointKind.SELECTOR : PointKind.WORLD;
            String selector = kind == PointKind.SELECTOR ? "@p" : null;
            return new PointSourceSpec(kind, selector, null, null, 0, null, EntityPoint.FOOT,
                    PointOffset.NONE, freeze);
        }
        return build(PointKind.fromOrigin(origin), origin, x, y, z, EntityPoint.FOOT,
                PointOffset.NONE, freeze);
    }

    /**
     * 通用点源（连线端点 / 注视目标 / 方向源载荷）→ 规格。
     *
     * @param value  形态字符串；{@code null} / 空串 = 无点源
     * @param x      {@code coordinate} 形态的 X
     * @param y      {@code coordinate} 形态的 Y
     * @param z      {@code coordinate} 形态的 Z
     * @param freeze 冻结开关
     * @param point  实体形态的取点（其余形态不读）；{@code null} = 脚底中心
     * @param offset 一层偏移；{@code null} = 无偏移
     * @return 点源规格；空串 / {@code null} = {@code null}
     */
    public static PointSourceSpec point(String value, double x, double y, double z, boolean freeze,
                                        EntityPoint point, PointOffset offset) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return build(PointKind.fromOrigin(value), value, x, y, z, point, offset, freeze);
    }

    /** 形态 + 载荷 → 规格（{@code kind == null} = 无点源）。 */
    private static PointSourceSpec build(PointKind kind, String value, double x, double y, double z,
                                         EntityPoint point, PointOffset offset, boolean freeze) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case WORLD -> new PointSourceSpec(kind, null, null, null, 0, null, point, offset, freeze);
            case COORDINATE -> new PointSourceSpec(kind, null, null, null, 0, new Vec3(x, y, z), point, offset,
                    freeze);
            case SELECTOR -> new PointSourceSpec(kind, value, null, null, 0, null, point, offset, freeze);
            case BLOCK -> {
                PointKinds.BlockRef block = PointKinds.parseBlock(value);
                yield new PointSourceSpec(kind, null, null, block.blockId(), block.radius(), null, point, offset,
                        freeze);
            }
            case STRUCTURE -> new PointSourceSpec(kind, null, value, null, 0, null, point, offset, freeze);
        };
    }

    /** 构造期派生签名：全部字段的函数，只在这里拼一次。 */
    private static String derive(PointKind kind, String selector, String structureId, String blockId,
                                 int blockRadius, Vec3 coordinate, EntityPoint entityPoint,
                                 PointOffset offset, boolean freeze) {
        StringBuilder builder = new StringBuilder(96);
        builder.append(kind == null ? "" : kind.name()).append(SEP);
        append(builder, selector);
        append(builder, structureId);
        append(builder, blockId);
        builder.append(blockRadius).append(SEP);
        append(builder, coordinate);
        builder.append(entityPoint.xPct()).append(',').append(entityPoint.yPct()).append(',')
                .append(entityPoint.zPct()).append(SEP);
        append(builder, offset == null ? null : offset.vector());
        builder.append(offset == null ? "" : offset.space().name()).append(SEP);
        builder.append(freeze ? '1' : '0');
        return builder.toString();
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null) {
            builder.append(value);
        }
        builder.append(SEP);
    }

    private static void append(StringBuilder builder, Vec3 vector) {
        if (vector != null) {
            builder.append(vector.x).append(',').append(vector.y).append(',').append(vector.z);
        }
        builder.append(SEP);
    }
}
