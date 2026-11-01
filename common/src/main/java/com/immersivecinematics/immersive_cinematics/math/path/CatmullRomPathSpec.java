package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Catmull-Rom 路径（过点插值）：点序列 = {@code [from, interior..., to]}，曲线经过每个点，
 * 段间在共享切线下 C1（对参数 {@code t} 而言）。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#ENDPOINTS}。
 *
 * <p>参数化 {@code t}：{@code t_0 = 0}、{@code t_{i+1} = t_i + max(|P_{i+1}-P_i|, 1e-9)^α}，
 * α 由 {@link Parameterization} 给出（uniform 0 / chordal 1 / centripetal 0.5）。α=0 时距离项恒为 1，
 * 即等参数间隔。{@code 1e-9} 地板保证相邻点重合时仍得到正参数间隔，不出现 0 除。
 *
 * <p>端点幽灵点（{@link EndpointMode}）：{@code MIRROR} 取 {@code P_{-1}=2P_0-P_1}、
 * {@code P_{n+1}=2P_n-P_{n-1}}；{@code CLAMP} 取 {@code P_{-1}=P_0}、{@code P_{n+1}=P_n}。
 * 幽灵参数 {@code t_{-1}=t_0-max(|P_0-P_{-1}|,1e-9)^α}、{@code t_{n+1}=t_n+max(|P_{n+1}-P_n|,1e-9)^α}。
 *
 * <p>切线（Barry–Goldman，再乘 {@code tension}）：
 * {@code m_i = ((P_i-P_{i-1})/(t_i-t_{i-1})·(t_{i+1}-t_i) + (P_{i+1}-P_i)/(t_{i+1}-t_i)·(t_i-t_{i-1}))/(t_{i+1}-t_{i-1})}；
 * 等距点且 uniform 时退化为 {@code (P_{i+1}-P_{i-1})/2}。
 *
 * <p>段求值：全局 {@code u∈[0,1]} → {@code g = u·n}、{@code i = min(n-1, floor(g))}、{@code s = g-i}；
 * 段 i 用三次 Hermite（端点 {@code P_i/P_{i+1}}，切线 {@code m_i·(t_{i+1}-t_i)} / {@code m_{i+1}·(t_{i+1}-t_i)}，
 * 即把对 {@code t} 的导数换算到局部 {@code [0,1]} 参数）。位置 / 一阶 / 二阶导均为解析式，
 * {@link #speed(double)} 与 {@link PathExpression#derivative(double)} 共用同一组私有标量辅助方法，零分配。
 *
 * <p>连续性口径：曲线对参数 {@code t} 是 C1（相邻两段在 {@code P_i} 处共享同一个 {@code m_i}）。
 * 由于 {@code u} 按段数均分、而各段的 {@code t} 间隔不必相等，{@code dP/du} 在段边界按
 * {@code (t_{i+1}-t_i)/(t_i-t_{i-1})} 的比值跳变——这是“局部参数缩放”的固有结果，几何切线方向仍连续；
 * 只有等距点（三种参数化都退化为等间隔）时 {@code dP/du} 才逐点连续。
 *
 * <p>退化：相邻点重合由 {@code 1e-9} 地板兜住，不产生 NaN/Inf；全部点重合时切线恒为零向量，
 * 位置恒等于该点、弧长为 0。
 */
public final class CatmullRomPathSpec implements PathSpec {

    /** 参数间隔的距离地板：避免重合点造成零分母。 */
    private static final double MIN_DISTANCE = 1e-9;

    /** 参数化：α 决定相邻点距离如何折算成参数间隔。 */
    public enum Parameterization {

        /** α = 0：等参数间隔，距离不影响。 */
        UNIFORM(0.0),

        /** α = 1：参数间隔 = 弦长。 */
        CHORDAL(1.0),

        /** α = 0.5：参数间隔 = 弦长平方根，最稳（缺省）。 */
        CENTRIPETAL(0.5);

        private final double alpha;

        Parameterization(double alpha) {
            this.alpha = alpha;
        }

        /** 距离的幂次 α。 */
        public double alpha() {
            return alpha;
        }

        /** JSON 名 → 枚举；未知取值 / null 抛 {@link IllegalArgumentException}（不回落缺省）。 */
        public static Parameterization fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("参数化不能为 null");
            }
            return switch (value) {
                case "uniform" -> UNIFORM;
                case "chordal" -> CHORDAL;
                case "centripetal" -> CENTRIPETAL;
                default -> throw new IllegalArgumentException(
                        "未知参数化 '" + value + "'（可选: uniform, chordal, centripetal）");
            };
        }
    }

    /** 端点幽灵点策略。 */
    public enum EndpointMode {

        /** 镜像：{@code P_{-1}=2P_0-P_1}、{@code P_{n+1}=2P_n-P_{n-1}}（缺省，首末段更自然）。 */
        MIRROR,

        /** 钳制：{@code P_{-1}=P_0}、{@code P_{n+1}=P_n}（首末段切线减半）。 */
        CLAMP;

        /** JSON 名 → 枚举；未知取值 / null 抛 {@link IllegalArgumentException}（不回落缺省）。 */
        public static EndpointMode fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("端点策略不能为 null");
            }
            return switch (value) {
                case "mirror" -> MIRROR;
                case "clamp" -> CLAMP;
                default -> throw new IllegalArgumentException(
                        "未知端点策略 '" + value + "'（可选: mirror, clamp）");
            };
        }
    }

    /** 内部过点（不含首末端点），{@code relative} 时相对段起点 {@code from} 解析。 */
    private final List<PathPoint> interiorPoints;

    private final Parameterization parameterization;

    /** 张力，{@code [0,1]}；1 = 标准 Catmull-Rom，0 = 切线全零（各段退化为缓入缓出的直线）。 */
    private final double tension;

    private final EndpointMode endpoint;

    /**
     * @param interiorPoints 内部过点（首末端点由 {@link #bind(Vec3, Vec3)} 的关键帧给出），非 null、无 null 元素
     * @param parameterization 参数化，非 null
     * @param tension 张力，必须在 {@code [0,1]} 内
     * @param endpoint 端点幽灵点策略，非 null
     * @throws IllegalArgumentException 任一参数非法
     */
    public CatmullRomPathSpec(List<PathPoint> interiorPoints, Parameterization parameterization,
                              double tension, EndpointMode endpoint) {
        if (interiorPoints == null) {
            throw new IllegalArgumentException("内部过点列表不能为 null");
        }
        for (int i = 0; i < interiorPoints.size(); i++) {
            if (interiorPoints.get(i) == null) {
                throw new IllegalArgumentException("内部过点不能为 null: index=" + i);
            }
        }
        if (parameterization == null) {
            throw new IllegalArgumentException("参数化不能为 null");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("端点策略不能为 null");
        }
        if (!(tension >= 0.0 && tension <= 1.0)) {
            throw new IllegalArgumentException("tension 必须在 [0,1] 内: " + tension);
        }
        this.interiorPoints = List.copyOf(interiorPoints);
        this.parameterization = parameterization;
        this.tension = tension;
        this.endpoint = endpoint;
    }

    @Override
    public String type() {
        return "catmull_rom";
    }

    @Override
    public PathDomain domain() {
        return PathDomain.UNIT;
    }

    @Override
    public PathAnchor anchor() {
        return PathAnchor.ENDPOINTS;
    }

    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("端点不能为 null: from=" + from + ", to=" + to);
        }
        int pointCount = interiorPoints.size() + 2;
        double[] px = new double[pointCount];
        double[] py = new double[pointCount];
        double[] pz = new double[pointCount];
        px[0] = from.x;
        py[0] = from.y;
        pz[0] = from.z;
        for (int i = 0; i < interiorPoints.size(); i++) {
            Vec3 point = interiorPoints.get(i).resolve(from);
            px[i + 1] = point.x;
            py[i + 1] = point.y;
            pz[i + 1] = point.z;
        }
        int last = pointCount - 1;
        px[last] = to.x;
        py[last] = to.y;
        pz[last] = to.z;
        return new CatmullRomExpression(parameterization, tension, endpoint, px, py, pz);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 内部过点（不含首末端点）。 */
    public List<PathPoint> interiorPoints() {
        return interiorPoints;
    }

    public Parameterization parameterization() {
        return parameterization;
    }

    /** 张力 {@code [0,1]}。 */
    public double tension() {
        return tension;
    }

    public EndpointMode endpoint() {
        return endpoint;
    }

    private static final class CatmullRomExpression implements PathExpression {

        /** 段数 {@code n >= 1}。 */
        private final int segments;

        /** 过点坐标（含端点），长度 {@code n+1}。 */
        private final double[] px;
        private final double[] py;
        private final double[] pz;

        /** 每段首端切线（已换算到局部 {@code [0,1]} 参数），长度 {@code n}。 */
        private final double[] t0x;
        private final double[] t0y;
        private final double[] t0z;

        /** 每段末端切线（已换算到局部 {@code [0,1]} 参数），长度 {@code n}。 */
        private final double[] t1x;
        private final double[] t1y;
        private final double[] t1z;

        CatmullRomExpression(Parameterization parameterization, double tension, EndpointMode endpoint,
                             double[] px, double[] py, double[] pz) {
            int pointCount = px.length;
            int last = pointCount - 1;
            this.segments = last;
            this.px = px;
            this.py = py;
            this.pz = pz;

            // 幽灵点：P_{-1}（首端之外）与 P_{n+1}（末端之外）
            double ghostStartX;
            double ghostStartY;
            double ghostStartZ;
            double ghostEndX;
            double ghostEndY;
            double ghostEndZ;
            if (endpoint == EndpointMode.MIRROR) {
                ghostStartX = 2.0 * px[0] - px[1];
                ghostStartY = 2.0 * py[0] - py[1];
                ghostStartZ = 2.0 * pz[0] - pz[1];
                ghostEndX = 2.0 * px[last] - px[last - 1];
                ghostEndY = 2.0 * py[last] - py[last - 1];
                ghostEndZ = 2.0 * pz[last] - pz[last - 1];
            } else {
                ghostStartX = px[0];
                ghostStartY = py[0];
                ghostStartZ = pz[0];
                ghostEndX = px[last];
                ghostEndY = py[last];
                ghostEndZ = pz[last];
            }

            double alpha = parameterization.alpha();
            double[] t = new double[pointCount];
            t[0] = 0.0;
            for (int i = 0; i < last; i++) {
                t[i + 1] = t[i] + step(px[i], py[i], pz[i], px[i + 1], py[i + 1], pz[i + 1], alpha);
            }
            double tGhostStart = t[0] - step(px[0], py[0], pz[0],
                    ghostStartX, ghostStartY, ghostStartZ, alpha);
            double tGhostEnd = t[last] + step(px[last], py[last], pz[last],
                    ghostEndX, ghostEndY, ghostEndZ, alpha);

            // Barry–Goldman 切线 m_i（对参数 t 的导数，再乘 tension）
            double[] mx = new double[pointCount];
            double[] my = new double[pointCount];
            double[] mz = new double[pointCount];
            for (int i = 0; i < pointCount; i++) {
                double prevT = i == 0 ? tGhostStart : t[i - 1];
                double nextT = i == last ? tGhostEnd : t[i + 1];
                double prevX = i == 0 ? ghostStartX : px[i - 1];
                double prevY = i == 0 ? ghostStartY : py[i - 1];
                double prevZ = i == 0 ? ghostStartZ : pz[i - 1];
                double nextX = i == last ? ghostEndX : px[i + 1];
                double nextY = i == last ? ghostEndY : py[i + 1];
                double nextZ = i == last ? ghostEndZ : pz[i + 1];
                mx[i] = tension * tangent(prevX, px[i], nextX, prevT, t[i], nextT);
                my[i] = tension * tangent(prevY, py[i], nextY, prevT, t[i], nextT);
                mz[i] = tension * tangent(prevZ, pz[i], nextZ, prevT, t[i], nextT);
            }

            // 段切线：m_i·(t_{i+1}-t_i) 把对 t 的导数换算到局部 [0,1] 参数
            this.t0x = new double[segments];
            this.t0y = new double[segments];
            this.t0z = new double[segments];
            this.t1x = new double[segments];
            this.t1y = new double[segments];
            this.t1z = new double[segments];
            for (int i = 0; i < segments; i++) {
                double span = t[i + 1] - t[i];
                t0x[i] = mx[i] * span;
                t0y[i] = my[i] * span;
                t0z[i] = mz[i] * span;
                t1x[i] = mx[i + 1] * span;
                t1y[i] = my[i + 1] * span;
                t1z[i] = mz[i + 1] * span;
            }
        }

        /** 参数间隔 {@code max(|P_{i+1}-P_i|, 1e-9)^α}；α=0 时恒为 1。 */
        private static double step(double ax, double ay, double az,
                                   double bx, double by, double bz, double alpha) {
            double dx = bx - ax;
            double dy = by - ay;
            double dz = bz - az;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return Math.pow(Math.max(distance, MIN_DISTANCE), alpha);
        }

        /**
         * Barry–Goldman 切线（对参数 t 的导数）：
         * {@code ((P_i-P_{i-1})/(t_i-t_{i-1})·(t_{i+1}-t_i) + (P_{i+1}-P_i)/(t_{i+1}-t_i)·(t_i-t_{i-1}))/(t_{i+1}-t_{i-1})}。
         * 分母由 {@code 1e-9} 地板保证为正，不会出现 0 除。
         */
        private static double tangent(double prev, double current, double next,
                                      double prevT, double currentT, double nextT) {
            double left = currentT - prevT;
            double right = nextT - currentT;
            double span = nextT - prevT;
            return ((current - prev) / left * right + (next - current) / right * left) / span;
        }

        @Override
        public String type() {
            return "catmull_rom";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            double g = unit(u) * segments;
            int i = segment(g);
            double s = g - i;
            double s2 = s * s;
            double s3 = s2 * s;
            double h00 = 2.0 * s3 - 3.0 * s2 + 1.0;
            double h10 = s3 - 2.0 * s2 + s;
            double h01 = -2.0 * s3 + 3.0 * s2;
            double h11 = s3 - s2;
            return new Vec3(
                    dx(i, h00, h10, h01, h11),
                    dy(i, h00, h10, h01, h11),
                    dz(i, h00, h10, h01, h11));
        }

        @Override
        public Vec3 derivative(double u) {
            double g = unit(u) * segments;
            int i = segment(g);
            double s = g - i;
            double s2 = s * s;
            double d00 = 6.0 * (s2 - s);
            double d10 = 3.0 * s2 - 4.0 * s + 1.0;
            double d01 = -6.0 * (s2 - s);
            double d11 = 3.0 * s2 - 2.0 * s;
            // 链式：s = u·n → dP/du = n·dP/ds
            return new Vec3(
                    segments * dx(i, d00, d10, d01, d11),
                    segments * dy(i, d00, d10, d01, d11),
                    segments * dz(i, d00, d10, d01, d11));
        }

        /** 与 {@link #derivative(double)} 逐项同构的标量求值，不构造向量。 */
        @Override
        public double speed(double u) {
            double g = unit(u) * segments;
            int i = segment(g);
            double s = g - i;
            double s2 = s * s;
            double d00 = 6.0 * (s2 - s);
            double d10 = 3.0 * s2 - 4.0 * s + 1.0;
            double d01 = -6.0 * (s2 - s);
            double d11 = 3.0 * s2 - 2.0 * s;
            double vx = segments * dx(i, d00, d10, d01, d11);
            double vy = segments * dy(i, d00, d10, d01, d11);
            double vz = segments * dz(i, d00, d10, d01, d11);
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            double g = unit(u) * segments;
            int i = segment(g);
            double s = g - i;
            double dd00 = 12.0 * s - 6.0;
            double dd10 = 6.0 * s - 4.0;
            double dd01 = -12.0 * s + 6.0;
            double dd11 = 6.0 * s - 2.0;
            // 链式：d²P/du² = n²·d²P/ds²
            double scale = (double) segments * segments;
            return new Vec3(
                    scale * dx(i, dd00, dd10, dd01, dd11),
                    scale * dy(i, dd00, dd10, dd01, dd11),
                    scale * dz(i, dd00, dd10, dd01, dd11));
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }

        /** 段 i 的 Hermite 组合 {@code c00·P_i + c10·T0_i + c01·P_{i+1} + c11·T1_i}（x 轴）。 */
        private double dx(int i, double c00, double c10, double c01, double c11) {
            return c00 * px[i] + c10 * t0x[i] + c01 * px[i + 1] + c11 * t1x[i];
        }

        private double dy(int i, double c00, double c10, double c01, double c11) {
            return c00 * py[i] + c10 * t0y[i] + c01 * py[i + 1] + c11 * t1y[i];
        }

        private double dz(int i, double c00, double c10, double c01, double c11) {
            return c00 * pz[i] + c10 * t0z[i] + c01 * pz[i + 1] + c11 * t1z[i];
        }

        /** u 夹到 {@code [0,1]}（段索引必须落在 {@code [0, n]} 内）；NaN 取 0。 */
        private static double unit(double u) {
            if (!(u > 0.0)) {
                return 0.0;
            }
            return u < 1.0 ? u : 1.0;
        }

        /** {@code g∈[0, n]} → 段索引 {@code [0, n-1]}（u=1 落到末段的 s=1）。 */
        private int segment(double g) {
            int i = (int) Math.floor(g);
            return i >= segments ? segments - 1 : i;
        }
    }
}
