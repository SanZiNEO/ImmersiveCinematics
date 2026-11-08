package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 路径数学工具：向量插值 / 叉积 / 模长、5 点 Gauss-Legendre 积分（节点与权重为私有常量）。
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
}
