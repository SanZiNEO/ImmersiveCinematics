package com.immersivecinematics.immersive_cinematics.math;

/**
 * 角度 helper — 单位 = 度；ANGLE 域的差值 / 混合一律取最短路径，绝对输出不强制归一。
 */
public final class AngleUtil {

    private AngleUtil() {} // 工具类，禁止实例化

    /**
     * 把角度折进 (-180, 180]。
     *
     * @param degrees 任意角度（度）
     * @return 同角度的最短表达（度），∈ (-180, 180]
     */
    public static double wrap180(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped > 180.0) {
            return wrapped - 360.0;
        }
        if (wrapped <= -180.0) {
            return wrapped + 360.0;
        }
        return wrapped;
    }

    /**
     * 从 {@code from} 到 {@code to} 的最短角差（度）。
     *
     * @param from 起点角度（度）
     * @param to   终点角度（度）
     * @return 最短路径差值（度），∈ (-180, 180]
     */
    public static double delta(double from, double to) {
        return wrap180(to - from);
    }
}
