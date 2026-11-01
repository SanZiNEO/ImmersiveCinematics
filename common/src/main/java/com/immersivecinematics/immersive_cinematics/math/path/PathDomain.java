package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * 路径参数定义域：闭区间 {@code [min, max]}，两端必须有限且 {@code min < max}。
 * 越界处置由 {@link #clamp(double)} / {@link #normalize(double)} 定义：NaN 取 min，±Inf 取对应端点。
 * {@link #UNIT} 是 {@code [0,1]}，Bézier / 线性路径的常用定义域。
 */
public record PathDomain(double min, double max) {

    /** {@code [0, 1]} 定义域。 */
    public static final PathDomain UNIT = new PathDomain(0.0, 1.0);

    public PathDomain {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("定义域端点必须有限: min=" + min + ", max=" + max);
        }
        if (min >= max) {
            throw new IllegalArgumentException("定义域要求 min < max: min=" + min + ", max=" + max);
        }
    }

    /** 区间宽度 {@code max - min}，恒为正。 */
    public double span() {
        return max - min;
    }

    /** u 是否落在闭区间内；NaN 返回 {@code false}。 */
    public boolean contains(double u) {
        return u >= min && u <= max;
    }

    /** 夹取到闭区间；NaN 取 {@code min}，{@code +Inf} 取 {@code max}，{@code -Inf} 取 {@code min}。 */
    public double clamp(double u) {
        if (Double.isNaN(u)) {
            return min;
        }
        if (u <= min) {
            return min;
        }
        if (u >= max) {
            return max;
        }
        return u;
    }

    /** clamp 后映射到 0 ~ 1：{@code (clamp(u) - min) / span()}。 */
    public double normalize(double u) {
        return (clamp(u) - min) / span();
    }
}
