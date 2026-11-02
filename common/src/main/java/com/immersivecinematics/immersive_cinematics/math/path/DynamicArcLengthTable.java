package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 动态曲线的弧长表缓存：控制点/权重/节点在小范围内变化时复用旧表，变化大才重建，连续抖动时降级为数值积分。
 *
 * <p>与 {@link ArcLengthTable} 的分工：{@code ArcLengthTable} 是「一条已绑定曲线的一张不可变表」；
 * 本类是「一条会变的曲线 + 它当前那张表」的持有者。调用方（I 轮动态路径 / 编辑器直控）为每条动态路径
 * 持有一个实例，每帧 {@link #update(PathExpression)} 传入新绑定的表达式：</p>
 * <ul>
 *   <li><b>复用</b>：新表达式与上次的指纹（{@value #FINGERPRINT_SAMPLES} 个等距采样位置）逐点距离都
 *       ≤ 阈值 → 保留旧表（旧表是旧几何的弧长，小变化下误差在阈值量级），只替换表达式；</li>
 *   <li><b>重建</b>：超过阈值 → 用新表达式重新 {@link ArcLengthTable}；</li>
 *   <li><b>降级</b>：连续重建超过 {@value #MAX_CONSECUTIVE_REBUILDS} 次（控制点每帧大幅抖动）→ 丢弃表，
 *       查询走「固定采样积分 + 二分反解」，避免每帧建表拖垮主线程。降级后保持到 {@link #reset()}
 *       或一次稳定更新（指纹回到阈值内）后自动恢复建表。</li>
 * </ul>
 *
 * <p>查询期零分配：有表时复用 {@link ArcLengthTable#uAt(double)}；降级时只用局部变量。指纹采样在
 * {@link #update(PathExpression)} 里分配少量 {@link Vec3}，频率 = 更新频率，不在逐帧查询路径上。</p>
 *
 * <p>线程：仅客户端主线程（与 {@link PathEvaluator} 一致）。</p>
 */
public final class DynamicArcLengthTable {

    /** 缺省变化阈值（世界单位）：任一等距采样点的位置偏移上限。 */
    public static final double DEFAULT_CHANGE_THRESHOLD = 0.25;

    /** 指纹采样点数（含定义域两端）。 */
    private static final int FINGERPRINT_SAMPLES = 5;

    /** 连续重建多少次后降级。 */
    private static final int MAX_CONSECUTIVE_REBUILDS = 8;

    /** 降级反解的二分迭代次数。 */
    private static final int DEGRADED_BISECTIONS = 32;

    private PathExpression expression;

    /** 当前表；{@code null} = 降级或尚未 update。 */
    private ArcLengthTable table;

    private final double[] fingerprint = new double[FINGERPRINT_SAMPLES * 3];

    private boolean hasFingerprint;
    private boolean degraded;
    private int consecutiveRebuilds;
    private int rebuilds;
    private int reuses;

    /** 用缺省阈值更新到新的绑定表达式。 */
    public void update(PathExpression expression) {
        update(expression, DEFAULT_CHANGE_THRESHOLD);
    }

    /**
     * 更新到新的绑定表达式，并按阈值决定复用 / 重建 / 降级。
     *
     * @param expression 新表达式（非 null）
     * @param threshold  任一等距采样点的位置偏移上限（世界单位，必须有限且 ≥ 0）
     */
    public void update(PathExpression expression, double threshold) {
        if (expression == null) {
            throw new IllegalArgumentException("expression 不能为 null");
        }
        if (!Double.isFinite(threshold) || threshold < 0.0) {
            throw new IllegalArgumentException("threshold 必须是有限的非负值: " + threshold);
        }
        double[] next = fingerprint(expression);
        boolean changed = !hasFingerprint || maxDistance(next, fingerprint) > threshold;

        if (!changed) {
            this.expression = expression;
            reuses++;
            // 降级中遇到稳定几何：恢复建表（降级只针对持续抖动）
            if (degraded) {
                degraded = false;
                table = new ArcLengthTable(expression);
                consecutiveRebuilds = 0;
            }
            return;
        }

        this.expression = expression;
        System.arraycopy(next, 0, fingerprint, 0, next.length);
        hasFingerprint = true;
        rebuilds++;
        if (++consecutiveRebuilds > MAX_CONSECUTIVE_REBUILDS) {
            degraded = true;
            table = null;
        } else {
            degraded = false;
            table = new ArcLengthTable(expression);
        }
    }

    /** 清除全部状态（下一次 update 必定重建）。 */
    public void reset() {
        expression = null;
        table = null;
        hasFingerprint = false;
        degraded = false;
        consecutiveRebuilds = 0;
        rebuilds = 0;
        reuses = 0;
    }

    /** 归一化弧长 {@code s∈[0,1]} 处的位置；未 update 过时抛 {@link IllegalStateException}。 */
    public Vec3 position(double s) {
        requireExpression();
        double u = uAt(s);
        return expression.position(u);
    }

    /** 归一化弧长 {@code s} 处的一阶导（未归一化，单位 / u）。 */
    public Vec3 tangent(double s) {
        requireExpression();
        double u = uAt(s);
        return expression.derivative(u);
    }

    /** 归一化弧长反解；有表走表，降级走固定采样积分 + 二分。 */
    public double uAt(double s) {
        requireExpression();
        double clamped = s < 0.0 ? 0.0 : Math.min(s, 1.0);
        if (table != null) {
            return table.uAt(clamped);
        }
        PathDomain domain = expression.domain();
        double total = ArcLength.length(expression);
        if (total <= 0.0) {
            return domain.min();
        }
        double target = clamped * total;
        double lo = domain.min();
        double hi = domain.max();
        for (int i = 0; i < DEGRADED_BISECTIONS; i++) {
            double mid = 0.5 * (lo + hi);
            double length = ArcLength.length(expression, domain.min(), mid);
            if (length < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** 是否有可复用的表（false = 降级或尚未 update）。 */
    public boolean hasTable() {
        return table != null;
    }

    /** 是否处于降级状态（连续大幅抖动，查询走数值积分）。 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 累计重建次数（诊断 / 探针）。 */
    public int rebuilds() {
        return rebuilds;
    }

    /** 累计复用次数（诊断 / 探针）。 */
    public int reuses() {
        return reuses;
    }

    private void requireExpression() {
        if (expression == null) {
            throw new IllegalStateException("DynamicArcLengthTable 尚未 update");
        }
    }

    /** 等距采样位置指纹（含两端；共 {@value #FINGERPRINT_SAMPLES} 点 × xyz）。 */
    private static double[] fingerprint(PathExpression expression) {
        PathDomain domain = expression.domain();
        double[] out = new double[FINGERPRINT_SAMPLES * 3];
        for (int i = 0; i < FINGERPRINT_SAMPLES; i++) {
            double u = FINGERPRINT_SAMPLES == 1
                    ? domain.min()
                    : domain.min() + (domain.max() - domain.min()) * i / (FINGERPRINT_SAMPLES - 1);
            Vec3 point = expression.position(u);
            int base = i * 3;
            out[base] = point.x;
            out[base + 1] = point.y;
            out[base + 2] = point.z;
        }
        return out;
    }

    /** 两个指纹逐点的最大欧氏距离。 */
    private static double maxDistance(double[] a, double[] b) {
        double max = 0.0;
        for (int i = 0; i < a.length; i += 3) {
            double dx = a[i] - b[i];
            double dy = a[i + 1] - b[i + 1];
            double dz = a[i + 2] - b[i + 2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > max) max = d;
        }
        return max;
    }
}
