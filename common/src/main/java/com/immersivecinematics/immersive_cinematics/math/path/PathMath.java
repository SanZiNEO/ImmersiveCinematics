package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 路径数学工具：向量插值 / 叉积 / 模长、Bernstein 基函数、5 点 Gauss-Legendre 节点与权重。
 * 5 点积分区间为 [-1,1]；{@link #gaussLegendre5()} 返回新数组，内部积分使用私有常量。
 */
public final class PathMath {

    /** 5 点 Gauss-Legendre 节点（[-1,1] 对称）。 */
    private static final double[] GL5_NODES = {
            -0.9061798459386640, -0.5384693101056831, 0.0,
            0.5384693101056831, 0.9061798459386640
    };

    /** 5 点 Gauss-Legendre 权重（与 {@link #GL5_NODES} 对应）。 */
    private static final double[] GL5_WEIGHTS = {
            0.2369268850561891, 0.4786286704993665, 0.5688888888888889,
            0.4786286704993665, 0.2369268850561891
    };

    private PathMath() {
    }

    /** 线性插值 {@code a + (b - a) * t}；t 不夹取。 */
    public static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }

    /** 向量模长。 */
    public static double length(Vec3 v) {
        return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    }

    /** 叉积 {@code a × b}。 */
    public static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(
                a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x);
    }

    /** 三个分量是否都有限。 */
    public static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /**
     * Bernstein 基函数 {@code C(degree,index) * u^index * (1-u)^(degree-index)}。
     * degree 0 ~ 3 为常用范围，更大次数同样按二项式求值。
     */
    public static double bernstein(int degree, int index, double u) {
        if (degree < 0) {
            throw new IllegalArgumentException("degree 不能为负: " + degree);
        }
        if (index < 0 || index > degree) {
            throw new IllegalArgumentException("index 必须在 0 ~ " + degree + ": index=" + index);
        }
        if (degree == 0) {
            return 1.0;
        }
        return binomial(degree, index) * Math.pow(u, index) * Math.pow(1.0 - u, degree - index);
    }

    /**
     * 5 点 Gauss-Legendre 节点与权重，顺序 {@code {x0..x4, w0..w4}}，积分区间 [-1,1]。
     * 返回新数组，调用方可自由修改。
     */
    public static double[] gaussLegendre5() {
        double[] out = new double[10];
        System.arraycopy(GL5_NODES, 0, out, 0, 5);
        System.arraycopy(GL5_WEIGHTS, 0, out, 5, 5);
        return out;
    }

    /**
     * 5 点 Gauss-Legendre 积分 {@code |P'|}，区间 {@code [a, b]}，{@code a <= b}。
     * 被积函数取 {@link PathExpression#speed(double)}，不构造向量，查询期零分配。
     */
    static double integrateSpeed(PathExpression expression, double a, double b) {
        double half = 0.5 * (b - a);
        double mid = 0.5 * (a + b);
        double sum = 0.0;
        for (int i = 0; i < 5; i++) {
            double u = mid + half * GL5_NODES[i];
            sum += GL5_WEIGHTS[i] * expression.speed(u);
        }
        return sum * half;
    }

    private static double binomial(int n, int k) {
        int m = k;
        if (m > n - m) {
            m = n - m;
        }
        double result = 1.0;
        for (int i = 1; i <= m; i++) {
            result = result * (n - m + i) / i;
        }
        return result;
    }
}
