package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * 轴约束选择：{@code true} = 该轴保留曲线自身分量，{@code false} = 该轴被约束为端点间线性。
 * 构造器不校验；三项全 {@code true} 即 {@link #ALL}（无约束）。
 */
public record AxisConstraint(boolean x, boolean y, boolean z) {

    /** 三轴均保留曲线分量，即无约束。 */
    public static final AxisConstraint ALL = new AxisConstraint(true, true, true);

    /** 是否至少有一个轴被约束为线性。 */
    public boolean constrainsAny() {
        return !x || !y || !z;
    }
}
