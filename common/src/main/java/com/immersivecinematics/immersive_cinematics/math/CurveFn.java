package com.immersivecinematics.immersive_cinematics.math;

/**
 * 逐分量曲线函数 — 无状态纯函数（同输入 → 同输出）。
 */
@FunctionalInterface
public interface CurveFn {

    /** 求值一个分量；定义域与越界处置由各实现声明（见 {@link Curves} / {@link CubicBezierCurve}）。 */
    double at(double x);
}
