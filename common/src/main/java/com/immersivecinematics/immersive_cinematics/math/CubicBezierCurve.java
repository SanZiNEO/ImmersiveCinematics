package com.immersivecinematics.immersive_cinematics.math;

/**
 * CSS cubic-bezier 曲线 — 控制点 P0=(0,0)、P1=(x1,y1)、P2=(x2,y2)、P3=(1,1)。
 *
 * 输入 x = 归一化进度 [0,1]（越界钳制），输出 y（可越界 = 允许过冲；y1 / y2 的范围见 {@link #clampY}）。
 * 求值 = 先解 x(t) = x 的 t（牛顿迭代 8 次，未收敛时二分兜底；容差 = x 残差 1e-7），再取 y(t)。
 * 参数不可变、无状态：同输入 → 同输出（不依赖迭代历史）。缺省控制点 = 恒等曲线。
 */
public final class CubicBezierCurve implements CurveFn {

    public static final double DEFAULT_X1 = 1.0 / 3.0;
    public static final double DEFAULT_Y1 = 1.0 / 3.0;
    public static final double DEFAULT_X2 = 2.0 / 3.0;
    public static final double DEFAULT_Y2 = 2.0 / 3.0;

    /** 控制点 x 范围：x 必须落在 [0,1] 内，x(t) 才在 [0,1] 上单调、求值唯一。 */
    public static final double MIN_X = 0.0;
    public static final double MAX_X = 1.0;

    /** 控制点 y 范围：允许过冲（y < 0 / y > 1），±4 以外视为数据错误。 */
    public static final double MIN_Y = -4.0;
    public static final double MAX_Y = 4.0;

    private static final int NEWTON_ITERATIONS = 8;
    private static final double SOLVE_TOLERANCE = 1.0e-7;
    private static final int BISECTION_MAX_ITERATIONS = 32;

    private final double ax;
    private final double bx;
    private final double cx;
    private final double ay;
    private final double by;
    private final double cy;

    /** 控制点越界按 {@link #clampX} / {@link #clampY} 钳制（范围常量在模型实现内，单源）。 */
    public CubicBezierCurve(double x1, double y1, double x2, double y2) {
        double cx1 = clampX(x1);
        double cy1 = clampY(y1);
        double cx2 = clampX(x2);
        double cy2 = clampY(y2);
        this.cx = 3.0 * cx1;
        this.bx = 3.0 * (cx2 - cx1) - this.cx;
        this.ax = 1.0 - this.cx - this.bx;
        this.cy = 3.0 * cy1;
        this.by = 3.0 * (cy2 - cy1) - this.cy;
        this.ay = 1.0 - this.cy - this.by;
    }

    /** 控制点 x：非有限 / 越界 → 钳制到 [0,1]。 */
    public static double clampX(double v) {
        if (!(v > MIN_X)) {
            return MIN_X;
        }
        return Math.min(v, MAX_X);
    }

    /** 控制点 y：NaN → 0（域内中性值）；越界 → 钳制到 [-4,4]。 */
    public static double clampY(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(MIN_Y, Math.min(v, MAX_Y));
    }

    @Override
    public double at(double x) {
        return sampleY(solveT(Curves.clamp01(x)));
    }

    /** 解 x(t) = x：起点 t = x；8 次未收敛时二分兜底（x(t) 在 [0,1] 单调、根唯一）。 */
    private double solveT(double x) {
        double t = x;
        for (int i = 0; i < NEWTON_ITERATIONS; i++) {
            double slope = slopeX(t);
            if (slope == 0.0) {
                break;
            }
            double error = sampleX(t) - x;
            if (Math.abs(error) < SOLVE_TOLERANCE) {
                return t;
            }
            t -= error / slope;
        }
        double low = 0.0;
        double high = 1.0;
        double mid = x;
        for (int i = 0; i < BISECTION_MAX_ITERATIONS && low < high; i++) {
            double value = sampleX(mid);
            if (Math.abs(value - x) < SOLVE_TOLERANCE) {
                return mid;
            }
            if (x > value) {
                low = mid;
            } else {
                high = mid;
            }
            mid = low + (high - low) * 0.5;
        }
        return mid;
    }

    private double sampleX(double t) {
        return ((ax * t + bx) * t + cx) * t;
    }

    private double sampleY(double t) {
        return ((ay * t + by) * t + cy) * t;
    }

    private double slopeX(double t) {
        return (3.0 * ax * t + 2.0 * bx) * t + cx;
    }
}
