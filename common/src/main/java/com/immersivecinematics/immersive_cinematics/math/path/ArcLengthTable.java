package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * 通用弧长表：把曲线参数 u 与归一化弧长 s ∈ [0,1] 建成单调括位，并借表达式速度剖面做反解。
 * 构建分两遍：先按 {@value #LENGTH_ESTIMATE_SEGMENTS} 段 5 点 Gauss-Legendre 预估算总长，把相对容差折成绝对容差；
 * 再对定义域递归二分，节点累积弧长由逐叶（对半两段）5 点 GL 积分 {@code |P'|} 累加。
 * 细分判据是叶内 {@code |弦长 - GL|} 相对**总长**的偏差：判据不随叶尺度收紧，任何曲线都必然收敛；
 * 深度与节点数双重封顶，触顶即停不抛错。
 * 查询精度由 {@link #uAt(double)} 的牛顿迭代保证（归一化残差 ≤ 1e-9），与表密度解耦；
 * 表密度只影响查询成本与初值质量，节点上限只用来挡病态曲线。表不改变曲线几何，查询期不分配。
 * 零长曲线：{@link #uAt(double)} 返回定义域起点。
 */
public final class ArcLengthTable {

    /** 缺省相对细分容差：叶内 {@code |弦长 - GL|} 相对整条曲线总长的上限（尺度无关）。 */
    public static final double DEFAULT_RELATIVE_TOLERANCE = 1e-6;

    /** 缺省最大递归深度。 */
    public static final int DEFAULT_MAX_DEPTH = 16;

    /** 表项数量上限（含首节点）；细分到此即停，不抛错。 */
    public static final int MAX_NODES = 1024;

    /** 总长预估的分段数：定义域均分这么多段，每段 5 点 GL 累加。 */
    private static final int LENGTH_ESTIMATE_SEGMENTS = 64;

    /** 绝对容差地板，防零长曲线把容差压成 0。 */
    private static final double ABSOLUTE_FLOOR = 1e-12;

    /** 低于此速度停止牛顿修正。 */
    private static final double MIN_SPEED = 1e-12;

    /** {@link #uAt(double)} 的归一化残差目标。 */
    private static final double RESIDUAL_TOLERANCE = 1e-9;

    /** {@link #uAt(double)} 的牛顿迭代上限。 */
    private static final int MAX_ITERATIONS = 8;

    private final PathExpression expression;
    private final PathDomain domain;
    private final double[] us;
    private final double[] raws;
    private final double totalLength;

    /** 用缺省相对容差与最大深度建表。 */
    public ArcLengthTable(PathExpression expression) {
        this(expression, DEFAULT_RELATIVE_TOLERANCE, DEFAULT_MAX_DEPTH);
    }

    /**
     * 建表：{@code relativeTolerance} 是相对**总长**的叶误差上限，必须有限且大于 0；{@code maxDepth} 取 0 ~ 30。
     * {@code expression} 为 null 或参数非法时抛 {@link IllegalArgumentException}。
     */
    public ArcLengthTable(PathExpression expression, double relativeTolerance, int maxDepth) {
        if (expression == null) {
            throw new IllegalArgumentException("expression 不能为 null");
        }
        if (!Double.isFinite(relativeTolerance) || relativeTolerance <= 0.0) {
            throw new IllegalArgumentException("relativeTolerance 必须是正的有限值: " + relativeTolerance);
        }
        if (maxDepth < 0 || maxDepth > 30) {
            throw new IllegalArgumentException("maxDepth 必须在 0 ~ 30: " + maxDepth);
        }
        this.expression = expression;
        this.domain = expression.domain();
        double min = domain.min();
        double max = domain.max();

        // 第一遍：粗估总长，把尺度无关的相对容差折成绝对容差；判据因此不随叶尺度收紧。
        double lengthEstimate = estimateLength(expression, min, max);
        if (!(lengthEstimate > 0.0)) {
            // 零长曲线：单节点表；弧长坐标恒为定义域起点。
            this.us = new double[]{min};
            this.raws = new double[]{0.0};
            this.totalLength = 0.0;
            return;
        }
        double absTolerance = Math.max(lengthEstimate * relativeTolerance, ABSOLUTE_FLOOR);

        // 第二遍：递归细分；落叶弧长取对半两段 GL 之和，逐叶累积成总长。
        Builder builder = new Builder(expression, absTolerance, maxDepth);
        builder.start(min);
        double chord = PathMath.length(expression.position(max).subtract(expression.position(min)));
        double gl = PathMath.integrateSpeed(expression, min, max);
        builder.subdivide(min, max, 0, chord, gl, MAX_NODES - 1);

        this.us = builder.us();
        this.raws = builder.raws();
        this.totalLength = raws[us.length - 1];
    }

    /** 总长预估：定义域均分 {@value #LENGTH_ESTIMATE_SEGMENTS} 段，每段 5 点 GL 积分 {@code |P'|}。 */
    private static double estimateLength(PathExpression expression, double min, double max) {
        double span = max - min;
        if (!(span > 0.0)) {
            return 0.0;
        }
        double step = span / LENGTH_ESTIMATE_SEGMENTS;
        double sum = 0.0;
        for (int i = 0; i < LENGTH_ESTIMATE_SEGMENTS; i++) {
            double a = min + step * i;
            double b = i == LENGTH_ESTIMATE_SEGMENTS - 1 ? max : a + step;
            sum += PathMath.integrateSpeed(expression, a, b);
        }
        return sum;
    }

    /** 总弧长，世界单位；零长曲线返回 0。 */
    public double length() {
        return totalLength;
    }

    /**
     * 归一化弧长 s 反解参数 u：s 先 clamp 到 [0,1]，表上二分加叶内线性插值取初值，
     * 再做至多 8 次牛顿迭代（每步重新定位所在叶、重算归一化弧长），返回残差最小的 u。
     * 零长曲线返回定义域起点。
     */
    public double uAt(double s) {
        if (totalLength <= 0.0) {
            return domain.min();
        }
        int last = us.length - 1;
        if (Double.isNaN(s) || s <= 0.0) {
            return us[0];
        }
        if (s >= 1.0) {
            return us[last];
        }
        double target = s * totalLength;
        int lo = 0;
        int hi = last;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (raws[mid] < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double uLo = us[lo];
        double uHi = us[hi];
        double rLo = raws[lo];
        double rHi = raws[hi];
        // 叶仍可能很宽：叶内再二分一次，把线性初值的残差压低约 4 倍。
        double uMid = 0.5 * (uLo + uHi);
        double rMid = rLo + PathMath.integrateSpeed(expression, uLo, uMid);
        if (target >= rMid) {
            uLo = uMid;
            rLo = rMid;
        } else {
            uHi = uMid;
            rHi = rMid;
        }
        double range = rHi - rLo;
        double u = range > 0.0 ? uLo + (uHi - uLo) * ((target - rLo) / range) : uHi;
        double bestU = u;
        double bestResidual = Double.POSITIVE_INFINITY;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            int leaf = leafOf(u);
            double speed = expression.speed(u);
            if (!(speed > MIN_SPEED)) {
                break;
            }
            double sTrue = (raws[leaf] + PathMath.integrateSpeed(expression, us[leaf], u)) / totalLength;
            double residual = sTrue - s;
            double magnitude = Math.abs(residual);
            if (magnitude < bestResidual) {
                bestResidual = magnitude;
                bestU = u;
            }
            if (bestResidual <= RESIDUAL_TOLERANCE) {
                break;
            }
            double next = u - residual * totalLength / speed;
            if (!domain.contains(next)) {
                break;
            }
            u = next;
        }
        return bestU;
    }

    /** 表项数量，即叶节点边界数；≤ {@link #MAX_NODES}。 */
    public int size() {
        return us.length;
    }

    /** u 处未夹取的归一化弧长：{@code (raws[lo] + [us[lo], u] 的 GL 积分) / totalLength}。 */
    private double normalizedAt(double u) {
        int lo = leafOf(u);
        return (raws[lo] + PathMath.integrateSpeed(expression, us[lo], u)) / totalLength;
    }

    /** 二分定位 u 所在叶：返回满足 {@code us[lo] <= u <= us[lo + 1]} 的 lo。 */
    private int leafOf(double u) {
        int lo = 0;
        int hi = us.length - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (us[mid] <= u) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 建表状态：递归细分并累积叶节点边界与原始弧长。 */
    private static final class Builder {

        private final PathExpression expression;
        private final double absTolerance;
        private final int maxDepth;
        private final double[] us = new double[MAX_NODES];
        private final double[] raws = new double[MAX_NODES];
        private int size;
        private double cumulative;

        Builder(PathExpression expression, double absTolerance, int maxDepth) {
            this.expression = expression;
            this.absTolerance = absTolerance;
            this.maxDepth = maxDepth;
        }

        void start(double u) {
            us[0] = u;
            raws[0] = 0.0;
            size = 1;
        }

        void subdivide(double ua, double ub, int depth, double chord, double gl, int budget) {
            // 触顶（深度 / 节点预算）时已无法再细分，直接用单叶 GL 值；budget 恒 ≥ 1，写入不越界。
            if (depth >= maxDepth || budget < 2) {
                leaf(ub, gl);
                return;
            }
            double um = 0.5 * (ua + ub);
            double glLeft = PathMath.integrateSpeed(expression, ua, um);
            double glRight = PathMath.integrateSpeed(expression, um, ub);
            if (converged(chord, gl)) {
                // 判据只看弦-弧偏差，在「速度剖面有折角」的叶上看不见积分误差；
                // 叶值改取两半之和（复合 10 点），同样的节点数把叶积分误差再降一档。
                leaf(ub, glLeft + glRight);
                return;
            }
            int entry = size;
            Vec3 pa = expression.position(ua);
            Vec3 pm = expression.position(um);
            Vec3 pb = expression.position(ub);
            double chordLeft = PathMath.length(pm.subtract(pa));
            double chordRight = PathMath.length(pb.subtract(pm));
            // 左子树最多用掉一半预算，右子树拿剩下的：未用掉的预算顺流给右兄弟。
            subdivide(ua, um, depth + 1, chordLeft, glLeft, budget / 2);
            subdivide(um, ub, depth + 1, chordRight, glRight, budget - (size - entry));
        }

        /** 落一片叶：累积弧长增加 {@code length}，记下叶边界。 */
        private void leaf(double ub, double length) {
            cumulative += length;
            us[size] = ub;
            raws[size] = cumulative;
            size++;
        }

        /** 叶判据：{@code |弦长 - GL| <= absTolerance}，容差按总长折算，与叶尺度无关。 */
        private boolean converged(double chord, double gl) {
            return Math.abs(chord - gl) <= absTolerance;
        }

        double[] us() {
            return Arrays.copyOf(us, size);
        }

        double[] raws() {
            return Arrays.copyOf(raws, size);
        }
    }
}
