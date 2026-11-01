package com.immersivecinematics.immersive_cinematics.math.path;

import java.util.Arrays;

/**
 * B-spline 基函数与 de Boor 求值：只服务 {@link BSplinePathSpec}，包内可见。
 *
 * <p>约定与 Cox–de Boor 一致：{@code n+1} 个控制点、次数 {@code p}、非减节点向量
 * {@code t_0 ≤ … ≤ t_{n+p+1}}，定义域 {@code [t_p, t_{n+1}]}。首末各 {@code p+1} 个节点相等时曲线
 * 在端点取首末控制点（钳制）。
 *
 * <p>de Boor 需要 {@code p+1} 个工作格，而 {@link PathExpression#speed(double)} 逐帧调用不得分配；
 * 工作行因此放在线程本地缓冲里复用：同线程串行调用结果与每次新分配完全一致，不同线程互不干扰，
 * 表达式实例本身仍是无状态的。
 */
final class BSplineBasis {

    /** de Boor 工作行缓冲的初始长度（覆盖最常见的 1~7 次，超出即扩容一次）。 */
    private static final int INITIAL_WORK = 8;

    /** 每线程的 de Boor 工作行；只在长度不足时扩容。 */
    private static final ThreadLocal<double[]> WORK = ThreadLocal.withInitial(() -> new double[INITIAL_WORK]);

    private BSplineBasis() {
    }

    /**
     * 返回满足 {@code knots[span] ≤ u < knots[span+1]} 的 span。
     * {@code u} 先 clamp 到 {@code [knots[degree], knots[controlCount]]}（NaN 取下端）；
     * 右端点 {@code u ≥ knots[controlCount]} 直接返回 {@code controlCount-1}（钳制末节点）。
     *
     * @param knots        节点向量，长度必须为 {@code controlCount + degree + 1}
     * @param degree       次数 {@code p}
     * @param controlCount 控制点个数 {@code n+1}
     * @param u            曲线参数
     */
    static int findSpan(double[] knots, int degree, int controlCount, double u) {
        double lower = knots[degree];
        double upper = knots[controlCount];
        double value = u >= lower ? u : lower; // NaN / 下越界都落到下端
        if (value >= upper) {
            return controlCount - 1;
        }
        // 不变量：knots[degree] ≤ value < knots[controlCount]，二分必然收敛（NURBS Book A2.1）
        int low = degree;
        int high = controlCount;
        int mid = (low + high) >>> 1;
        while (value < knots[mid] || value >= knots[mid + 1]) {
            if (value < knots[mid]) {
                high = mid;
            } else {
                low = mid;
            }
            mid = (low + high) >>> 1;
        }
        return mid;
    }

    /**
     * 标量 de Boor：求单个分量在 {@code u} 处的值（等价于 {@code Σ N_{i,p}(u)·ctrl[i]}）。
     * {@code u} 先 clamp 到 {@code [knots[degree], knots[controlCount]]}；零分母的 {@code α} 取 0。
     *
     * @param ctrl   单个分量的控制点值，长度 = 控制点个数
     * @param degree 次数 {@code p}（{@code 0} 合法：结果恒为唯一控制点）
     * @param knots  节点向量，长度 {@code ctrl.length + degree + 1}
     * @param u      曲线参数
     */
    static double deBoor(double[] ctrl, int degree, double[] knots, double u) {
        int controlCount = ctrl.length;
        double lower = knots[degree];
        double upper = knots[controlCount];
        double value = u >= lower ? u : lower; // NaN / 下越界都落到下端
        if (value >= upper) {
            value = upper;
        }
        int span = findSpan(knots, degree, controlCount, value);
        int first = span - degree;
        double[] work = work(degree + 1);
        for (int j = 0; j <= degree; j++) {
            work[j] = ctrl[first + j];
        }
        for (int r = 1; r <= degree; r++) {
            for (int j = degree; j >= r; j--) {
                int index = first + j;
                double denominator = knots[index + degree - r + 1] - knots[index];
                double alpha = denominator == 0.0 ? 0.0 : (value - knots[index]) / denominator;
                work[j] = (1.0 - alpha) * work[j - 1] + alpha * work[j];
            }
        }
        return work[degree];
    }

    /**
     * 导数控制点 {@code D_i = p·(c_{i+1}-c_i)/(t_{i+p+1}-t_{i+1})}，长度 {@code ctrl.length-1}。
     * 分母为 0（重复节点）的项取 0：该段曲线在节点处不可导，导数按 0 处理而非 NaN。
     *
     * @param ctrl   单个分量的控制点值
     * @param degree 次数 {@code p}（{@code ≥ 1}）
     * @param knots  原节点向量
     */
    static double[] derivativeControlPoints(double[] ctrl, int degree, double[] knots) {
        double[] derived = new double[ctrl.length - 1];
        for (int i = 0; i < derived.length; i++) {
            double denominator = knots[i + degree + 1] - knots[i + 1];
            derived[i] = denominator == 0.0 ? 0.0 : degree * (ctrl[i + 1] - ctrl[i]) / denominator;
        }
        return derived;
    }

    /** 导数曲线的节点向量：去掉首末各一项（长度 -2，与导数控制点数 / 次数匹配）。 */
    static double[] derivativeKnots(double[] knots) {
        return Arrays.copyOfRange(knots, 1, knots.length - 1);
    }

    /** 取长度至少 {@code size} 的线程本地工作行；不足时扩容一次后复用。 */
    private static double[] work(int size) {
        double[] work = WORK.get();
        if (work.length < size) {
            work = new double[size];
            WORK.set(work);
        }
        return work;
    }
}
