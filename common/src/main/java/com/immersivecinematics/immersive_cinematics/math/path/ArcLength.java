package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * 通用弧长算子：{@link AnalyticArcLength} 走解析快路径，其余用自适应 5 点 Gauss-Legendre 积分。
 * 数值路径从 16 段起，每段比较 5 点与两段 5 点之差，超相对容差 {@code 1e-9} 且深度未到 8 就二分。
 * 区间先 clamp 到定义域；{@code u0 >= u1} 返回 0。
 */
public final class ArcLength {

    private static final int INITIAL_SEGMENTS = 16;
    private static final double RELATIVE_TOLERANCE = 1e-9;
    private static final int MAX_DEPTH = 8;

    private ArcLength() {
    }

    /** 整条曲线的弧长，世界单位。 */
    public static double length(PathExpression expression) {
        if (expression == null) {
            throw new IllegalArgumentException("expression 不能为 null");
        }
        PathDomain domain = expression.domain();
        return length(expression, domain.min(), domain.max());
    }

    /** 区间 {@code [u0, u1]} 的弧长，世界单位；区间先 clamp 到定义域。 */
    public static double length(PathExpression expression, double u0, double u1) {
        if (expression == null) {
            throw new IllegalArgumentException("expression 不能为 null");
        }
        PathDomain domain = expression.domain();
        double a = domain.clamp(u0);
        double b = domain.clamp(u1);
        if (b <= a) {
            return 0.0;
        }
        if (expression instanceof AnalyticArcLength analytic) {
            return analytic.arcLength(a, b);
        }
        double total = 0.0;
        double step = (b - a) / INITIAL_SEGMENTS;
        for (int i = 0; i < INITIAL_SEGMENTS; i++) {
            double start = a + step * i;
            double end = i == INITIAL_SEGMENTS - 1 ? b : a + step * (i + 1);
            total += adaptive(expression, start, end, 0);
        }
        return total;
    }

    private static double adaptive(PathExpression expression, double a, double b, int depth) {
        double mid = 0.5 * (a + b);
        double whole = PathMath.integrateSpeed(expression, a, b);
        double half = PathMath.integrateSpeed(expression, a, mid)
                + PathMath.integrateSpeed(expression, mid, b);
        if (depth >= MAX_DEPTH || Math.abs(half - whole) <= RELATIVE_TOLERANCE * Math.abs(half)) {
            return half;
        }
        return adaptive(expression, a, mid, depth + 1)
                + adaptive(expression, mid, b, depth + 1);
    }
}
