package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

/**
 * 注视目标规格（{@code look_at}）：形态 + 载荷 + 一层偏移 + 冻结开关。
 *
 * <p>形态见 {@link LookAtKind}：{@link LookAtKind#PLAYER} 玩家激活位置；{@link LookAtKind#COORDINATE}
 * 固定坐标（{@link #structure()} 非空时取结构中心）；{@link LookAtKind#ENTITY} 实体
 * 包围盒内按 {@code look_at_part} 百分比取点（0 ~ 100，缺省 50/50/50）；{@link LookAtKind#BLOCK}
 * {@code block:<id>[:radius]} 就近搜索的方块中心。</p>
 *
 * <p>{@code look_at} 的模型语义是覆盖朝向结果：命中时 {@code yaw}/{@code pitch} 由当前世界位置到目标点
 * 的方向决定，{@code yaw}/{@code pitch} 偏移与方向锁都不参与；{@code roll} 仍取 {@code roll} 对象。</p>
 *
 * <p>{@link #signature()} 与 {@link PointSourceSpec#signature()} 同口径：构造期派生、运行期只读，
 * 供冻结桶键使用（最后一位是派生缓存，传入值会被重算）。</p>
 */
public record LookAtSpec(LookAtKind kind, String selector, double partX, double partY, double partZ,
                         double targetX, double targetY, double targetZ, String structure, String block,
                         PointOffset offset, boolean freeze, String signature) {

    /** 签名分隔符：单元分隔符。 */
    private static final char SEP = '\u001f';

    public LookAtSpec {
        kind = kind == null ? LookAtKind.PLAYER : kind;
        selector = selector == null || selector.isEmpty() ? "@p" : selector;
        structure = structure == null ? "" : structure;
        block = block == null ? "" : block;
        offset = offset == null ? PointOffset.NONE : offset;
        signature = derive(kind, selector, partX, partY, partZ, targetX, targetY, targetZ, structure, block, offset,
                freeze);
    }

    /** 常规构造：签名由其余字段在构造期派生，不接受调用方传入。 */
    public LookAtSpec(LookAtKind kind, String selector, double partX, double partY, double partZ,
                      double targetX, double targetY, double targetZ, String structure, String block,
                      PointOffset offset, boolean freeze) {
        this(kind, selector, partX, partY, partZ, targetX, targetY, targetZ, structure, block, offset, freeze, null);
    }

    /** 玩家激活位置（静态）。 */
    public static LookAtSpec player() {
        return new LookAtSpec(LookAtKind.PLAYER, "@p", 50, 50, 50, 0, 0, 0, "", "", PointOffset.NONE, false);
    }

    /** 固定世界坐标。 */
    public static LookAtSpec coordinate(double x, double y, double z) {
        return new LookAtSpec(LookAtKind.COORDINATE, "@p", 50, 50, 50, x, y, z, "", "", PointOffset.NONE,
                false);
    }

    /** 实体 + 包围盒内取点百分比（每轴 0 ~ 100，越界钳制、NaN = 50）。 */
    public static LookAtSpec entity(String selector, double partX, double partY, double partZ) {
        return new LookAtSpec(LookAtKind.ENTITY, selector, partX, partY, partZ, 0, 0, 0, "", "",
                PointOffset.NONE, false);
    }

    /** 方块点源（{@code block:<id>[:radius]}）。 */
    public static LookAtSpec block(String block) {
        return new LookAtSpec(LookAtKind.BLOCK, "@p", 50, 50, 50, 0, 0, 0, "", block, PointOffset.NONE,
                false);
    }

    /** 构造期派生签名：全部字段的函数，只在这里拼一次。 */
    private static String derive(LookAtKind kind, String selector, double partX, double partY, double partZ,
                                 double targetX, double targetY, double targetZ, String structure, String block,
                                 PointOffset offset, boolean freeze) {
        StringBuilder builder = new StringBuilder(96);
        builder.append(kind.name()).append(SEP);
        append(builder, selector);
        builder.append(partX).append(',').append(partY).append(',').append(partZ).append(SEP);
        builder.append(targetX).append(',').append(targetY).append(',').append(targetZ).append(SEP);
        append(builder, structure);
        append(builder, block);
        Vec3 vector = offset == null ? null : offset.vector();
        if (vector != null) {
            builder.append(vector.x).append(',').append(vector.y).append(',').append(vector.z);
        }
        builder.append(SEP).append(offset == null ? "" : offset.space().name()).append(SEP);
        builder.append(freeze ? '1' : '0');
        return builder.toString();
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null) {
            builder.append(value);
        }
        builder.append(SEP);
    }
}
