package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 位置规格：模式 + 偏移表达空间 + 点源 + 三轴分量。
 *
 * <p>语义（见 {@code track-camera.md} §3.2）：</p>
 * <ul>
 *   <li>{@link PositionMode#ABSOLUTE}：{@code (x, y, z)} 直接是世界坐标，无点源、无参考系；</li>
 *   <li>{@link PositionMode#RELATIVE} + {@link PointSpace#WORLD}：点源 + 世界轴偏移；</li>
 *   <li>{@link PositionMode#RELATIVE} + {@link PointSpace#BASE}：点源 + 基准系偏移，
 *       {@code (x, y, z)} 读作 {@code (fwd, up, right)}。</li>
 * </ul>
 *
 * <p>{@code relative} 且点源为 {@code null} 时按 {@link #space()} 补缺省（{@code world} = 玩家激活位置，
 * {@code base} = 隐式 {@code @p}），与 {@link PointSourceSpec#positionOrigin} 同一口径；这样求值层
 * 只会看到完整的相对规格，不在每帧重复判断"原点没写"。{@code absolute} 不补点源（validator 要求为空）。</p>
 */
public record PositionSpec(PositionMode mode, PointSpace space, PointSourceSpec origin, double x, double y,
                           double z) {

    public PositionSpec {
        mode = mode == null ? PositionMode.RELATIVE : mode;
        space = space == null ? PointSpace.WORLD : space;
        if (mode == PositionMode.RELATIVE && origin == null) {
            origin = PointSourceSpec.positionOrigin("", space, 0.0, 0.0, 0.0, false);
        }
    }

    /** 绝对位置：直接世界坐标。 */
    public static PositionSpec absolute(double x, double y, double z) {
        return new PositionSpec(PositionMode.ABSOLUTE, PointSpace.WORLD, null, x, y, z);
    }

    /** 相对位置：点源 + 偏移（{@code origin} 为 {@code null} = 按 {@code space} 补缺省点源）。 */
    public static PositionSpec relative(PointSpace space, PointSourceSpec origin, double x, double y, double z) {
        return new PositionSpec(PositionMode.RELATIVE, space, origin, x, y, z);
    }
}
