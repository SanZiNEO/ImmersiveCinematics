package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * Fresnel 积分 {@code C(z) = ∫₀^z cos(πt²/2) dt}、{@code S(z) = ∫₀^z sin(πt²/2) dt}。
 *
 * <p>两段求值，切换点按实测精度取 {@value #SERIES_LIMIT}：
 * <ul>
 *   <li>{@code |z| <= 3.53}：幂级数
 *       {@code C(z)=Σ(-1)^k (π/2)^{2k} z^{4k+1}/((4k+1)(2k)!)}、
 *       {@code S(z)=Σ(-1)^k (π/2)^{2k+1} z^{4k+3}/((4k+3)(2k+1)!)}，
 *       逐项累加到 {@code |term| < 1e-17}；级数在 z≈3.5 处项峰值约 1e6，舍入主导误差约 6e-10。</li>
 *   <li>{@code |z| > 3.53}：渐近展开（发散级数，最优截断）
 *       {@code C(z) = 1/2 + f·sin(πz²/2) - g·cos(πz²/2)}、
 *       {@code S(z) = 1/2 - f·cos(πz²/2) - g·sin(πz²/2)}，
 *       其中 {@code f = Σ(-1)^m (4m-1)!!/(πz)^{2m+1}}、{@code g = Σ(-1)^m (4m+1)!!/(πz)^{2m+3}}，
 *       展开到 {@code z^-11} 以上；z≥3.6 时余项 <2e-10，z≥4 时 <2e-12。</li>
 * </ul>
 * 两条分支在切换点的最大相对误差约 {@code 6e-10}（与 50 位参考值对拍，扫描 [0.02, 20] + 交叉区间 0.001 步长）。
 *
 * <p>奇函数：{@code C(-z) = -C(z)}、{@code S(-z) = -S(z)}；内部按 {@code |z|} 求值后取负号，
 * 因此渐近展开只需处理 {@code z > 0}。
 */
public final class Fresnel {

    /** 级数 / 渐近切换点：实测最优（两侧最大相对误差各约 6e-10，见类注释）。 */
    private static final double SERIES_LIMIT = 3.53;

    /** 级数累加终止阈值：{@code |term|} 低于它即停（结果量级 O(1)）。 */
    private static final double SERIES_EPSILON = 1e-17;

    /** 渐近展开的最小项阈值：低于双精度有效位后继续累加无意义。 */
    private static final double ASYMPTOTIC_EPSILON = 1e-18;

    /** 项数上限：级数在切换点内 ~25 项收敛；渐近最优截断 ~30 项。上限只防御异常输入。 */
    private static final int MAX_TERMS = 256;

    /** {@code π/2}。 */
    private static final double HALF_PI = Math.PI / 2.0;

    /** {@code (π/2)²}，级数相邻项的公共因子。 */
    private static final double HALF_PI_SQUARED = HALF_PI * HALF_PI;

    private Fresnel() {
    }

    /** {@code C(z) = ∫₀^z cos(πt²/2) dt}。 */
    public static double c(double z) {
        if (z == 0.0) {
            return 0.0;
        }
        double magnitude = Math.abs(z);
        double value = magnitude <= SERIES_LIMIT ? seriesC(magnitude) : asymptotic(magnitude, false);
        return z < 0.0 ? -value : value;
    }

    /** {@code S(z) = ∫₀^z sin(πt²/2) dt}。 */
    public static double s(double z) {
        if (z == 0.0) {
            return 0.0;
        }
        double magnitude = Math.abs(z);
        double value = magnitude <= SERIES_LIMIT ? seriesS(magnitude) : asymptotic(magnitude, true);
        return z < 0.0 ? -value : value;
    }

    /**
     * 幂级数 {@code C(z) = Σ (-1)^k (π/2)^{2k} z^{4k+1}/((4k+1)(2k)!)}，{@code z >= 0}。
     * 相邻项比 {@code term_{k+1}/term_k = -(π/2)² z⁴ (4k+1)/((4k+5)(2k+1)(2k+2))}。
     */
    private static double seriesC(double z) {
        double z2 = z * z;
        double factor = -HALF_PI_SQUARED * z2 * z2;
        double term = z;
        double sum = term;
        for (int k = 0; k < MAX_TERMS; k++) {
            term *= factor * (4.0 * k + 1.0) / ((4.0 * k + 5.0) * (2.0 * k + 1.0) * (2.0 * k + 2.0));
            sum += term;
            if (Math.abs(term) < SERIES_EPSILON) {
                break;
            }
        }
        return sum;
    }

    /**
     * 幂级数 {@code S(z) = Σ (-1)^k (π/2)^{2k+1} z^{4k+3}/((4k+3)(2k+1)!)}，{@code z >= 0}。
     * 相邻项比 {@code term_{k+1}/term_k = -(π/2)² z⁴ (4k+3)/((4k+7)(2k+2)(2k+3))}。
     */
    private static double seriesS(double z) {
        double z2 = z * z;
        double factor = -HALF_PI_SQUARED * z2 * z2;
        double term = HALF_PI * z * z2 / 3.0;
        double sum = term;
        for (int k = 0; k < MAX_TERMS; k++) {
            term *= factor * (4.0 * k + 3.0) / ((4.0 * k + 7.0) * (2.0 * k + 2.0) * (2.0 * k + 3.0));
            sum += term;
            if (Math.abs(term) < SERIES_EPSILON) {
                break;
            }
        }
        return sum;
    }

    /**
     * 渐近展开 {@code z > 3.56}（{@code cosinePart} 选 C 还是 S）。
     *
     * <p>第 n 项系数 {@code c_n = (2n-1)!!}（{@code c_0 = 1}），幅值 {@code t_n = c_n/(πz)^{2n+1}}；
     * 相位因子按 n 的奇偶在 {@code sin(πz²/2)} / {@code cos(πz²/2)} 之间交替，
     * 符号序列 {@code +,-,-,+,+,-,-,+…}（每两项翻转一次）。发散级数在 {@code |t_n|} 开始增大处截断。
     */
    private static double asymptotic(double z, boolean cosinePart) {
        double w = Math.PI * z * z;
        double phase = 0.5 * w;
        double sin = Math.sin(phase);
        double cos = Math.cos(phase);
        double term = 1.0 / (Math.PI * z);
        double sign = 1.0;
        double sum = 0.0;
        double previous = Double.POSITIVE_INFINITY;
        for (int n = 0; n < MAX_TERMS; n++) {
            if (n > 0) {
                term *= (2.0 * n - 1.0) / w;
                if ((n & 1) == 1) {
                    sign = -sign;
                }
            }
            if (term >= previous) {
                // 已过最小项：再加只会放大余项（发散级数的最优截断）。
                break;
            }
            double signed = sign * term;
            if (cosinePart) {
                sum += (n & 1) == 0 ? -signed * cos : signed * sin;
            } else {
                sum += (n & 1) == 0 ? signed * sin : signed * cos;
            }
            previous = term;
            if (term < ASYMPTOTIC_EPSILON) {
                break;
            }
        }
        return 0.5 + sum;
    }
}
