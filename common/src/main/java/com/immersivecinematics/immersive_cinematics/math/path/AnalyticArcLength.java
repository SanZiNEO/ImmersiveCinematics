package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * 解析弧长能力：区间 {@code [u0, u1]} 上的弧长，世界单位。
 * {@code u0} / {@code u1} 已 clamp 到定义域，调用方保证 {@code u0 <= u1}。
 */
public interface AnalyticArcLength {

    /** 区间弧长，世界单位；{@code u0 == u1} 返回 0。 */
    double arcLength(double u0, double u1);
}
