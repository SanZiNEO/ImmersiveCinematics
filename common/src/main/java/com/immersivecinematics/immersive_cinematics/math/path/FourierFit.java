package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Fourier 拟合：把一串采样位置投影到 {@link FourierPathSpec} 的两组基上，输出可直接构造曲线的系数。
 *
 * <p>两种形态的采样点与基底：
 * <ul>
 *   <li><b>周期</b>（{@code periodic = true}）：{@code u_i = T·i/n}（{@code i = 0..n-1}，末点不重复），
 *       基底 {@code {1, cos(2πk t), sin(2πk t)}}，未知数 {@code 2K+1}，直接拟合样本位置；</li>
 *   <li><b>非周期</b>（{@code periodic = false}）：{@code u_i = T·i/(n-1)}（含两端），端点取
 *       {@code samples[0] / samples[n-1]} 与给定速度构造三次趋势 {@code Q(t)}（{@link FourierPathSpec#trend}
 *       与本类共用同一算式），先减趋势，再对残差拟合受约束基底
 *       {@code {g_k = 1-cos(2πk t), h_k = 4·sin(2πk t)·t·(1-t)}}，未知数 {@code 2K}。</li>
 * </ul>
 *
 * <p>解法：每个分量独立解正规方程 {@code (AᵀA)c = Aᵀy}（规模 {@code 2K+1} 或 {@code 2K}），
 * 高斯消元 + 部分主元；主元相对矩阵量级低于 {@value #PIVOT_FLOOR} 视为奇异，抛
 * {@link IllegalArgumentException}（不静默给出无意义的系数）。三个分量共用同一消除过程（增广矩阵三列右端项，
 * 与逐分量独立求解等价）。
 *
 * <p>误差：返回拟合曲线在**全部样本**上的位置误差 {@code rms}（均方根）与 {@code maxError}（最大值），
 * 由构造出的 {@link FourierPathSpec} 逐点求值得到——与作者最终拿到的曲线同一条求值路径。
 *
 * <p>非法参数（样本 / 速度非 null 且有限、周期非正、阶数为负、样本数少于 {@code 2·order+2}）一律在入口抛
 * {@link IllegalArgumentException}。
 */
public final class FourierFit {

    /** 主元相对阈值：低于它与矩阵量级的乘积即视为奇异。 */
    private static final double PIVOT_FLOOR = 1e-12;

    /** {@code 2π}。 */
    private static final double TWO_PI = Math.PI * 2.0;

    private FourierFit() {
    }

    /**
     * 拟合结果。
     *
     * @param a0       常数项（周期形态的偏移；非周期形态恒为 {@link Vec3#ZERO}）
     * @param cos      余弦系数 {@code a_1..a_K}
     * @param sin      正弦系数 {@code b_1..b_K}
     * @param rms      全部样本位置误差的均方根
     * @param maxError 全部样本位置误差的最大值
     * @param order    阶数 {@code K}（= {@code cos.size() = sin.size()}）
     */
    public record Result(Vec3 a0, List<Vec3> cos, List<Vec3> sin, double rms, double maxError, int order) {

        public Result {
            cos = List.copyOf(cos);
            sin = List.copyOf(sin);
        }
    }

    /**
     * 拟合一条 Fourier 路径。
     *
     * @param samples       采样位置（至少 {@code 2·order+2} 个，全部有限）
     * @param period        参数周期 {@code T}（u 单位，有限正数）
     * @param order         阶数 {@code K}（≥ 0）
     * @param periodic      true = 周期级数；false = 三次趋势（端点取首末样本）+ 受约束残差
     * @param startVelocity 起点速度 {@code V0}（位置 / u，非周期形态参与趋势项）
     * @param endVelocity   终点速度 {@code V1}（位置 / u，非周期形态参与趋势项）
     * @return 系数与误差
     * @throws IllegalArgumentException 参数非法、样本不足或正规方程奇异
     */
    public static Result fit(List<Vec3> samples, double period, int order, boolean periodic,
                             Vec3 startVelocity, Vec3 endVelocity) {
        if (samples == null) {
            throw new IllegalArgumentException("样本列表不能为 null");
        }
        if (!Double.isFinite(period) || period <= 0.0) {
            throw new IllegalArgumentException("周期必须是正的有限值: period=" + period);
        }
        if (order < 0) {
            throw new IllegalArgumentException("阶数必须 ≥ 0: order=" + order);
        }
        int count = samples.size();
        int required = 2 * order + 2;
        if (count < required) {
            throw new IllegalArgumentException("样本数不足: order=" + order + " 至少需要 " + required
                    + " 个样本，实际 " + count);
        }
        for (int i = 0; i < count; i++) {
            Vec3 sample = samples.get(i);
            if (sample == null || !PathMath.isFinite(sample)) {
                throw new IllegalArgumentException("第 " + i + " 个样本必须是有限向量: " + sample);
            }
        }
        if (startVelocity == null || !PathMath.isFinite(startVelocity)) {
            throw new IllegalArgumentException("起点速度必须是有限向量: start_velocity=" + startVelocity);
        }
        if (endVelocity == null || !PathMath.isFinite(endVelocity)) {
            throw new IllegalArgumentException("终点速度必须是有限向量: end_velocity=" + endVelocity);
        }

        Vec3 from = samples.get(0);
        Vec3 to = samples.get(count - 1);
        int unknowns = periodic ? 2 * order + 1 : 2 * order;
        double[][] normal = new double[unknowns][unknowns];
        double[][] rhs = new double[unknowns][3];
        double[] row = new double[unknowns];

        for (int i = 0; i < count; i++) {
            // 周期形态末点不重复（t_i = i/n）；非周期形态含两端（t_i = i/(n-1)）。
            double t = (double) i / (periodic ? count : count - 1);
            if (periodic) {
                row[0] = 1.0;
                for (int k = 1; k <= order; k++) {
                    row[2 * k - 1] = Math.cos(TWO_PI * k * t);
                    row[2 * k] = Math.sin(TWO_PI * k * t);
                }
            } else {
                for (int k = 1; k <= order; k++) {
                    row[2 * k - 2] = FourierPathSpec.residualEven(k, t);
                    row[2 * k - 1] = FourierPathSpec.residualOdd(k, t);
                }
            }
            Vec3 target = periodic
                    ? samples.get(i)
                    : samples.get(i).subtract(FourierPathSpec.trend(from, to, startVelocity, endVelocity, t, period));
            for (int a = 0; a < unknowns; a++) {
                double value = row[a];
                if (value == 0.0) {
                    continue;
                }
                for (int b = 0; b < unknowns; b++) {
                    normal[a][b] += value * row[b];
                }
                rhs[a][0] += value * target.x;
                rhs[a][1] += value * target.y;
                rhs[a][2] += value * target.z;
            }
        }

        double[][] solution = solve(normal, rhs, unknowns);
        List<Vec3> cosValues = new ArrayList<>(order);
        List<Vec3> sinValues = new ArrayList<>(order);
        for (int k = 1; k <= order; k++) {
            int evenIndex = periodic ? 2 * k - 1 : 2 * k - 2;
            int oddIndex = periodic ? 2 * k : 2 * k - 1;
            cosValues.add(vector(solution[evenIndex]));
            sinValues.add(vector(solution[oddIndex]));
        }
        Vec3 a0 = periodic ? vector(solution[0]) : Vec3.ZERO;

        FourierPathSpec spec = new FourierPathSpec(a0, cosValues, sinValues, period, periodic,
                startVelocity, endVelocity);
        PathExpression expression = spec.bind(from, to);
        double square = 0.0;
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            double u = period * i / (periodic ? count : count - 1);
            double error = expression.position(u).distanceTo(samples.get(i));
            square += error * error;
            max = Math.max(max, error);
        }
        return new Result(a0, cosValues, sinValues, Math.sqrt(square / count), max, order);
    }

    private static Vec3 vector(double[] solved) {
        return new Vec3(solved[0], solved[1], solved[2]);
    }

    /**
     * 解 {@code size} 阶正规方程，三个右端项（x / y / z）一次消元完成。
     * 返回 {@code size × 3} 的解；主元过小抛 {@link IllegalArgumentException}。
     */
    private static double[][] solve(double[][] normal, double[][] rhs, int size) {
        double[][] augmented = new double[size][size + 3];
        double scale = 0.0;
        for (int r = 0; r < size; r++) {
            System.arraycopy(normal[r], 0, augmented[r], 0, size);
            System.arraycopy(rhs[r], 0, augmented[r], size, 3);
            for (int c = 0; c < size; c++) {
                scale = Math.max(scale, Math.abs(normal[r][c]));
            }
        }
        double threshold = scale * PIVOT_FLOOR;
        for (int col = 0; col < size; col++) {
            int pivot = col;
            double best = Math.abs(augmented[col][col]);
            for (int r = col + 1; r < size; r++) {
                double candidate = Math.abs(augmented[r][col]);
                if (candidate > best) {
                    best = candidate;
                    pivot = r;
                }
            }
            if (!(best > threshold)) {
                throw new IllegalArgumentException("正规方程奇异（第 " + col + " 列主元 " + best
                        + " ≤ " + threshold + "），样本无法确定 Fourier 系数");
            }
            double[] swap = augmented[col];
            augmented[col] = augmented[pivot];
            augmented[pivot] = swap;
            for (int r = col + 1; r < size; r++) {
                double factor = augmented[r][col] / augmented[col][col];
                if (factor == 0.0) {
                    continue;
                }
                for (int c = col; c < size + 3; c++) {
                    augmented[r][c] -= factor * augmented[col][c];
                }
            }
        }
        double[][] solved = new double[size][3];
        for (int col = size - 1; col >= 0; col--) {
            for (int w = 0; w < 3; w++) {
                double sum = augmented[col][size + w];
                for (int c = col + 1; c < size; c++) {
                    sum -= augmented[col][c] * solved[c][w];
                }
                solved[col][w] = sum / augmented[col][col];
            }
        }
        return solved;
    }
}
