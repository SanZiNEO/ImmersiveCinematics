package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Fourier 路径：有限项三角级数。两种形态共用同一组系数 {@code a0 / a_k / b_k}（{@code k = 1..K}，
 * {@code K = cos.size() = sin.size()}），定义域 {@code [0, T]}（{@code T = period}）。
 *
 * <p><b>周期形态</b>（{@code periodic = true}，锚点 {@link PathAnchor#FREE}，{@code from} / {@code to} 被忽略）：
 * <pre>
 * P(u) = a0 + Σ_{k=1..K} [ a_k·cos(2πk u/T) + b_k·sin(2πk u/T) ]
 * P'(u) = Σ_{k=1..K} (2πk/T)·[ b_k·cos(2πk u/T) - a_k·sin(2πk u/T) ]
 * P''(u) = -Σ_{k=1..K} (2πk/T)²·[ a_k·cos(2πk u/T) + b_k·sin(2πk u/T) ]</pre>
 * 级数天然满足 {@code P(0)=P(T)}、{@code P'(0)=P'(T)}、{@code P''(0)=P''(T)}：接缝处位置 / 速度 / 加速度连续。
 * 声明 {@link PathCapability#PERIODIC}，{@link PathExpression#period()} 返回 {@code T}，调用方先对 u 取模再求值；
 * 求值本身不做夹取——周期函数在定义域外的值就等于其模一个周期的值。
 *
 * <p><b>非周期形态</b>（{@code periodic = false}，锚点 {@link PathAnchor#ENDPOINTS}）：
 * <pre>
 * P(u) = Q(t) + F(t)，t = u/T
 * Q(t) = h00·from + h10·(T·V0) + h01·to + h11·(T·V1)     （三次 Hermite 趋势项）
 * F(t) = Σ_{k=1..K} [ a_k·g_k(t) + b_k·h_k(t) ]           （受约束 Fourier 残差）
 * g_k(t) = 1 - cos(2πk t)，h_k(t) = 4·sin(2πk t)·t·(1-t)</pre>
 * {@code h00 = 2t³-3t²+1}、{@code h10 = t³-2t²+t}、{@code h01 = -2t³+3t²}、{@code h11 = t³-t²}。
 * {@code V0 / V1} 即 {@code start_velocity / end_velocity}，单位「位置 / u」，因此 t 空间的趋势切线是
 * {@code T·V0}、{@code T·V1}——这样求导按链式除以 {@code T} 后正好回到 {@code V0 / V1}。
 *
 * <p>残差基底在两端的位置与一阶导恒为 0（{@code g_k(0)=g_k(1)=0}、{@code h_k(0)=h_k(1)=0}、
 * {@code g_k'(0)=g_k'(1)=h_k'(0)=h_k'(1)=0}），只改内部形状：{@code P(0)=from}、{@code P(T)=to}、
 * {@code P'(0)=V0}、{@code P'(T)=V1} 与系数无关。这条形态里常数项没有位置（{@code g_k} 已含常数分量），
 * 故要求 {@code a0 = 0}。
 *
 * <p>链式：非周期形态 {@code dP/du = (1/T)·d/dt}、{@code d²P/du² = (1/T²)·d²/dt²}；趋势项里
 * {@code h10·(T·V0)} 的 {@code T} 与链式的 {@code 1/T} 相消，导数直接用 {@code V0 / V1} 组合，
 * 端点速度因此不带额外的舍入（{@code derivative(0)} 逐位等于 {@code V0}）。
 *
 * <p>{@link #speed(double)} 与 {@link #derivative(double)} 同一算式，只做标量累加、逐帧零分配；
 * 二阶导解析，声明 {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}。
 *
 * <p>非法参数（系数 / 端点速度非有限、{@code cos} 与 {@code sin} 长度不等、周期非正或非有限、
 * 非周期形态下 {@code a0} 非零）一律在构造器抛 {@link IllegalArgumentException}，不静默回落。
 */
public final class FourierPathSpec implements PathSpec {

    /** {@code 2π}。 */
    private static final double TWO_PI = Math.PI * 2.0;

    private final Vec3 a0;
    private final List<Vec3> cos;
    private final List<Vec3> sin;
    private final double period;
    private final boolean periodic;
    private final Vec3 startVelocity;
    private final Vec3 endVelocity;
    private final PathDomain domain;

    /**
     * Fourier 路径定义。
     *
     * @param a0            常数项（周期形态的偏移；非周期形态必须为零向量）
     * @param cos           余弦系数 {@code a_1..a_K}（第 k 项对应阶数 k，有限）
     * @param sin           正弦系数 {@code b_1..b_K}（与 {@code cos} 等长，有限）
     * @param period        参数周期 {@code T}（u 单位，有限正数）
     * @param periodic      true = 周期级数（锚点 FREE）；false = 三次趋势 + 受约束残差（锚点 ENDPOINTS）
     * @param startVelocity 起点速度 {@code V0}（位置 / u，仅非周期形态生效）
     * @param endVelocity   终点速度 {@code V1}（位置 / u，仅非周期形态生效）
     * @throws IllegalArgumentException 任一项非法（消息带实际值）
     */
    public FourierPathSpec(Vec3 a0, List<Vec3> cos, List<Vec3> sin, double period, boolean periodic,
                           Vec3 startVelocity, Vec3 endVelocity) {
        if (a0 == null) {
            throw new IllegalArgumentException("常数项 a0 不能为 null");
        }
        if (cos == null || sin == null) {
            throw new IllegalArgumentException("cos / sin 系数列表不能为 null: cos=" + cos + ", sin=" + sin);
        }
        if (cos.size() != sin.size()) {
            throw new IllegalArgumentException("cos 与 sin 的阶数必须一致: cos=" + cos.size()
                    + ", sin=" + sin.size());
        }
        if (!Double.isFinite(period) || period <= 0.0) {
            throw new IllegalArgumentException("周期必须是正的有限值: period=" + period);
        }
        if (!PathMath.isFinite(a0)) {
            throw new IllegalArgumentException("常数项 a0 必须是有限向量: a0=" + a0);
        }
        for (int k = 0; k < cos.size(); k++) {
            Vec3 even = cos.get(k);
            Vec3 odd = sin.get(k);
            if (even == null || !PathMath.isFinite(even)) {
                throw new IllegalArgumentException("第 " + (k + 1) + " 阶余弦系数必须是有限向量: cos[" + k + "]=" + even);
            }
            if (odd == null || !PathMath.isFinite(odd)) {
                throw new IllegalArgumentException("第 " + (k + 1) + " 阶正弦系数必须是有限向量: sin[" + k + "]=" + odd);
            }
        }
        if (startVelocity == null || !PathMath.isFinite(startVelocity)) {
            throw new IllegalArgumentException("起点速度必须是有限向量: start_velocity=" + startVelocity);
        }
        if (endVelocity == null || !PathMath.isFinite(endVelocity)) {
            throw new IllegalArgumentException("终点速度必须是有限向量: end_velocity=" + endVelocity);
        }
        if (!periodic && (a0.x != 0.0 || a0.y != 0.0 || a0.z != 0.0)) {
            throw new IllegalArgumentException("非周期路径的 a0 必须为 0（残差基底已含常数分量）: a0=" + a0);
        }
        this.a0 = a0;
        this.cos = List.copyOf(cos);
        this.sin = List.copyOf(sin);
        this.period = period;
        this.periodic = periodic;
        this.startVelocity = startVelocity;
        this.endVelocity = endVelocity;
        this.domain = new PathDomain(0.0, period);
    }

    @Override
    public String type() {
        return "fourier";
    }

    @Override
    public PathDomain domain() {
        return domain;
    }

    /** 周期形态忽略调用点端点（FREE）；非周期形态由端点定位（ENDPOINTS）。 */
    @Override
    public PathAnchor anchor() {
        return periodic ? PathAnchor.FREE : PathAnchor.ENDPOINTS;
    }

    /** 周期形态的 {@code from} / {@code to} 被忽略；非周期形态两者参与趋势项。 */
    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (periodic) {
            return new PeriodicFourierExpression(a0, toArray(cos), toArray(sin), period, domain);
        }
        if (from == null || to == null || !PathMath.isFinite(from) || !PathMath.isFinite(to)) {
            throw new IllegalArgumentException("非周期 Fourier 路径的端点必须是有限坐标: from=" + from
                    + ", to=" + to);
        }
        return new AperiodicFourierExpression(from, to, startVelocity, endVelocity,
                toArray(cos), toArray(sin), period, domain);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE
                || (periodic && capability == PathCapability.PERIODIC);
    }

    /** 常数项（非周期形态恒为零向量）。 */
    public Vec3 a0() {
        return a0;
    }

    /** 余弦系数 {@code a_1..a_K}。 */
    public List<Vec3> cos() {
        return cos;
    }

    /** 正弦系数 {@code b_1..b_K}。 */
    public List<Vec3> sin() {
        return sin;
    }

    /** 阶数 {@code K = cos.size() = sin.size()}。 */
    public int order() {
        return cos.size();
    }

    /** 参数周期 {@code T}（u 单位）。 */
    public double period() {
        return period;
    }

    /** 是否周期形态。 */
    public boolean isPeriodic() {
        return periodic;
    }

    /** 起点速度 {@code V0}（位置 / u，仅非周期形态生效）。 */
    public Vec3 startVelocity() {
        return startVelocity;
    }

    /** 终点速度 {@code V1}（位置 / u，仅非周期形态生效）。 */
    public Vec3 endVelocity() {
        return endVelocity;
    }

    @Override
    public String toString() {
        return "FourierPathSpec{order=" + cos.size() + ", period=" + period + ", periodic=" + periodic + "}";
    }

    private static Vec3[] toArray(List<Vec3> values) {
        return values.toArray(new Vec3[0]);
    }

    // ========== 与 FourierFit 共用的基函数 ==========

    /**
     * 非周期趋势项 {@code Q(t) = h00·from + h10·(T·V0) + h01·to + h11·(T·V1)}。
     * 求值器与 {@link FourierFit} 共用这一算式，拟合减趋势与求值不会漂移。
     */
    static Vec3 trend(Vec3 from, Vec3 to, Vec3 startVelocity, Vec3 endVelocity, double t, double period) {
        double t2 = t * t;
        double t3 = t2 * t;
        double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
        double h10 = t3 - 2.0 * t2 + t;
        double h01 = -2.0 * t3 + 3.0 * t2;
        double h11 = t3 - t2;
        return new Vec3(
                h00 * from.x + h10 * (period * startVelocity.x) + h01 * to.x + h11 * (period * endVelocity.x),
                h00 * from.y + h10 * (period * startVelocity.y) + h01 * to.y + h11 * (period * endVelocity.y),
                h00 * from.z + h10 * (period * startVelocity.z) + h01 * to.z + h11 * (period * endVelocity.z));
    }

    /** 非周期残差的偶基底 {@code g_k(t) = 1 - cos(2πk t)}（两端位置与一阶导为 0）。 */
    static double residualEven(int k, double t) {
        return 1.0 - Math.cos(TWO_PI * k * t);
    }

    /** 非周期残差的奇基底 {@code h_k(t) = 4·sin(2πk t)·t·(1-t)}（两端位置与一阶导为 0）。 */
    static double residualOdd(int k, double t) {
        return 4.0 * Math.sin(TWO_PI * k * t) * t * (1.0 - t);
    }

    // ========== 周期形态 ==========

    /** 周期级数：{@code P(u) = a0 + Σ [a_k cos + b_k sin]}，对 u 取模后求值，不做夹取。 */
    private static final class PeriodicFourierExpression implements PathExpression {

        private final Vec3 a0;
        private final Vec3[] cos;
        private final Vec3[] sin;
        private final double period;
        private final PathDomain domain;

        PeriodicFourierExpression(Vec3 a0, Vec3[] cos, Vec3[] sin, double period, PathDomain domain) {
            this.a0 = a0;
            this.cos = cos;
            this.sin = sin;
            this.period = period;
            this.domain = domain;
        }

        @Override
        public String type() {
            return "fourier";
        }

        @Override
        public PathDomain domain() {
            return domain;
        }

        @Override
        public double period() {
            return period;
        }

        @Override
        public Vec3 position(double u) {
            double angle = TWO_PI * (u / period);
            double x = a0.x;
            double y = a0.y;
            double z = a0.z;
            for (int i = 0; i < cos.length; i++) {
                double theta = angle * (i + 1);
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                x += a.x * even + b.x * odd;
                y += a.y * even + b.y * odd;
                z += a.z * even + b.z * odd;
            }
            return new Vec3(x, y, z);
        }

        @Override
        public Vec3 derivative(double u) {
            double angle = TWO_PI * (u / period);
            double scale = TWO_PI / period;
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (int i = 0; i < cos.length; i++) {
                int k = i + 1;
                double theta = angle * k;
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                double weight = scale * k;
                x += weight * (b.x * even - a.x * odd);
                y += weight * (b.y * even - a.y * odd);
                z += weight * (b.z * even - a.z * odd);
            }
            return new Vec3(x, y, z);
        }

        /** 与 {@link #derivative(double)} 同一算式（只做标量累加，逐帧零分配），改一处必须同步另一处。 */
        @Override
        public double speed(double u) {
            double angle = TWO_PI * (u / period);
            double scale = TWO_PI / period;
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (int i = 0; i < cos.length; i++) {
                int k = i + 1;
                double theta = angle * k;
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                double weight = scale * k;
                x += weight * (b.x * even - a.x * odd);
                y += weight * (b.y * even - a.y * odd);
                z += weight * (b.z * even - a.z * odd);
            }
            return Math.sqrt(x * x + y * y + z * z);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            double angle = TWO_PI * (u / period);
            double scale = TWO_PI / period;
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (int i = 0; i < cos.length; i++) {
                int k = i + 1;
                double theta = angle * k;
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                double weight = -(scale * k) * (scale * k);
                x += weight * (a.x * even + b.x * odd);
                y += weight * (a.y * even + b.y * odd);
                z += weight * (a.z * even + b.z * odd);
            }
            return new Vec3(x, y, z);
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE
                    || capability == PathCapability.PERIODIC;
        }
    }

    // ========== 非周期形态 ==========

    /** 三次趋势 + 受约束残差；{@code t = u/T}，链式导数为 {@code 1/T}、{@code 1/T²}。 */
    private static final class AperiodicFourierExpression implements PathExpression {

        private final Vec3 from;
        private final Vec3 to;
        private final Vec3 startVelocity;
        private final Vec3 endVelocity;
        /** {@code T·V0}、{@code T·V1}：趋势项在 t 空间的切线（位置）。 */
        private final Vec3 trendStart;
        private final Vec3 trendEnd;
        private final Vec3[] cos;
        private final Vec3[] sin;
        private final double period;
        private final PathDomain domain;

        AperiodicFourierExpression(Vec3 from, Vec3 to, Vec3 startVelocity, Vec3 endVelocity,
                                   Vec3[] cos, Vec3[] sin, double period, PathDomain domain) {
            this.from = from;
            this.to = to;
            this.startVelocity = startVelocity;
            this.endVelocity = endVelocity;
            this.trendStart = startVelocity.scale(period);
            this.trendEnd = endVelocity.scale(period);
            this.cos = cos;
            this.sin = sin;
            this.period = period;
            this.domain = domain;
        }

        @Override
        public String type() {
            return "fourier";
        }

        @Override
        public PathDomain domain() {
            return domain;
        }

        @Override
        public Vec3 position(double u) {
            double t = domain.clamp(u) / period;
            double t2 = t * t;
            double t3 = t2 * t;
            double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
            double h10 = t3 - 2.0 * t2 + t;
            double h01 = -2.0 * t3 + 3.0 * t2;
            double h11 = t3 - t2;
            double x = h00 * from.x + h10 * trendStart.x + h01 * to.x + h11 * trendEnd.x;
            double y = h00 * from.y + h10 * trendStart.y + h01 * to.y + h11 * trendEnd.y;
            double z = h00 * from.z + h10 * trendStart.z + h01 * to.z + h11 * trendEnd.z;
            double angle = TWO_PI * t;
            double window = t * (1.0 - t);
            for (int i = 0; i < cos.length; i++) {
                double theta = angle * (i + 1);
                double even = 1.0 - Math.cos(theta);
                double odd = 4.0 * Math.sin(theta) * window;
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                x += a.x * even + b.x * odd;
                y += a.y * even + b.y * odd;
                z += a.z * even + b.z * odd;
            }
            return new Vec3(x, y, z);
        }

        @Override
        public Vec3 derivative(double u) {
            double t = domain.clamp(u) / period;
            double t2 = t * t;
            double d00 = 6.0 * (t2 - t);
            double d10 = 3.0 * t2 - 4.0 * t + 1.0;
            double d01 = -6.0 * (t2 - t);
            double d11 = 3.0 * t2 - 2.0 * t;
            // 趋势项：h10·(T·V0) 的 T 与链式的 1/T 相消，端点速度项直接用 V0 / V1。
            double x = (d00 * from.x + d01 * to.x) / period + d10 * startVelocity.x + d11 * endVelocity.x;
            double y = (d00 * from.y + d01 * to.y) / period + d10 * startVelocity.y + d11 * endVelocity.y;
            double z = (d00 * from.z + d01 * to.z) / period + d10 * startVelocity.z + d11 * endVelocity.z;
            double angle = TWO_PI * t;
            double window = t * (1.0 - t);
            double slope = 1.0 - 2.0 * t;
            double rx = 0.0;
            double ry = 0.0;
            double rz = 0.0;
            for (int i = 0; i < cos.length; i++) {
                int k = i + 1;
                double weight = TWO_PI * k;
                double theta = angle * k;
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                double dEven = weight * odd;
                double dOdd = 4.0 * (weight * even * window + odd * slope);
                rx += a.x * dEven + b.x * dOdd;
                ry += a.y * dEven + b.y * dOdd;
                rz += a.z * dEven + b.z * dOdd;
            }
            return new Vec3(x + rx / period, y + ry / period, z + rz / period);
        }

        /** 与 {@link #derivative(double)} 同一算式（只做标量累加，逐帧零分配），改一处必须同步另一处。 */
        @Override
        public double speed(double u) {
            double t = domain.clamp(u) / period;
            double t2 = t * t;
            double d00 = 6.0 * (t2 - t);
            double d10 = 3.0 * t2 - 4.0 * t + 1.0;
            double d01 = -6.0 * (t2 - t);
            double d11 = 3.0 * t2 - 2.0 * t;
            double x = (d00 * from.x + d01 * to.x) / period + d10 * startVelocity.x + d11 * endVelocity.x;
            double y = (d00 * from.y + d01 * to.y) / period + d10 * startVelocity.y + d11 * endVelocity.y;
            double z = (d00 * from.z + d01 * to.z) / period + d10 * startVelocity.z + d11 * endVelocity.z;
            double angle = TWO_PI * t;
            double window = t * (1.0 - t);
            double slope = 1.0 - 2.0 * t;
            double rx = 0.0;
            double ry = 0.0;
            double rz = 0.0;
            for (int i = 0; i < cos.length; i++) {
                int k = i + 1;
                double weight = TWO_PI * k;
                double theta = angle * k;
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                double dEven = weight * odd;
                double dOdd = 4.0 * (weight * even * window + odd * slope);
                rx += a.x * dEven + b.x * dOdd;
                ry += a.y * dEven + b.y * dOdd;
                rz += a.z * dEven + b.z * dOdd;
            }
            x += rx / period;
            y += ry / period;
            z += rz / period;
            return Math.sqrt(x * x + y * y + z * z);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            double t = domain.clamp(u) / period;
            double dd00 = 12.0 * t - 6.0;
            double dd10 = 6.0 * t - 4.0;
            double dd01 = -12.0 * t + 6.0;
            double dd11 = 6.0 * t - 2.0;
            double x = (dd00 * from.x + dd01 * to.x) / (period * period)
                    + (dd10 * startVelocity.x + dd11 * endVelocity.x) / period;
            double y = (dd00 * from.y + dd01 * to.y) / (period * period)
                    + (dd10 * startVelocity.y + dd11 * endVelocity.y) / period;
            double z = (dd00 * from.z + dd01 * to.z) / (period * period)
                    + (dd10 * startVelocity.z + dd11 * endVelocity.z) / period;
            double angle = TWO_PI * t;
            double window = t * (1.0 - t);
            double slope = 1.0 - 2.0 * t;
            double rx = 0.0;
            double ry = 0.0;
            double rz = 0.0;
            for (int i = 0; i < cos.length; i++) {
                int k = i + 1;
                double weight = TWO_PI * k;
                double square = weight * weight;
                double theta = angle * k;
                double even = Math.cos(theta);
                double odd = Math.sin(theta);
                Vec3 a = cos[i];
                Vec3 b = sin[i];
                double ddEven = square * even;
                double ddOdd = 4.0 * (-square * odd * window + 2.0 * weight * even * slope - 2.0 * odd);
                rx += a.x * ddEven + b.x * ddOdd;
                ry += a.y * ddEven + b.y * ddOdd;
                rz += a.z * ddEven + b.z * ddOdd;
            }
            double scale = period * period;
            return new Vec3(x + rx / scale, y + ry / scale, z + rz / scale);
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }
    }
}
