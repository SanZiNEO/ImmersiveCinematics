package com.immersivecinematics.immersive_cinematics.math;

/**
 * 曲线族公式 — 归一化进度 [0,1] → 值 [0,1] 的映射（{@link #linear} 例外：恒等、不钳制）。
 *
 * 输入越界：smoothstep / ease_* 先钳制到 [0,1] 再求值（= 端点延拓）；linear 原样返回。
 * 全部为无状态纯函数（同输入 → 同输出），step 路径零分配。
 */
public final class Curves {

    private Curves() {} // 工具类，禁止实例化

    /** 恒等（linear）：不钳制，输出 = 输入（回退目标 = 无效果）。 */
    public static double linear(double x) {
        return x;
    }

    /** 平滑插值 3t² - 2t³（Hermite）：两端一阶导为 0，缓入缓出。 */
    public static double smoothstep(double x) {
        double t = clamp01(x);
        return t * t * (3.0 - 2.0 * t);
    }

    /** 缓入 t²：起步慢、结束快。 */
    public static double easeIn(double x) {
        double t = clamp01(x);
        return t * t;
    }

    /** 缓出 1 - (1-t)²：起步快、结束慢。 */
    public static double easeOut(double x) {
        double t = clamp01(x);
        return 1.0 - (1.0 - t) * (1.0 - t);
    }

    /** 缓入缓出（两段二次）：中点速度最高，两端慢。 */
    public static double easeInOut(double x) {
        double t = clamp01(x);
        if (t < 0.5) {
            return 2.0 * t * t;
        }
        double u = 2.0 * t - 1.0;
        return 0.5 + 0.5 * (1.0 - (1.0 - u) * (1.0 - u));
    }

    /** 钳制到 [0,1]；NaN 原样通过（由调用层守门处置）。 */
    static double clamp01(double x) {
        if (x < 0.0) {
            return 0.0;
        }
        if (x > 1.0) {
            return 1.0;
        }
        return x;
    }
}
