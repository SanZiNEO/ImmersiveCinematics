package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 三次 Hermite 路径：{@code P0 / P1} 取调用点端点，{@code T0 / T1} 取构造给出的两端切线。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#ENDPOINTS}。
 *
 * <p>基函数 {@code h00 = 2u³-3u²+1}、{@code h10 = u³-2u²+u}、{@code h01 = -2u³+3u²}、
 * {@code h11 = u³-u²}；位置 {@code H(u) = h00·P0 + h10·T0 + h01·P1 + h11·T1}。
 * 于是 {@code H(0)=P0}、{@code H(1)=P1}、{@code H'(0)=T0}、{@code H'(1)=T1}；
 * 一阶/二阶导均为基函数的解析导数，{@link #speed(double)} 与 {@link PathExpression#derivative(double)}
 * 共用同一组私有标量辅助方法（{@code dx/dy/dz}），逐帧调用零分配。
 *
 * <p>切线解析：{@code T0 = tangentStart.resolve(from)}、{@code T1 = tangentEnd.resolve(to)}——
 * 两端切线各自相对自己的端点解析。{@code absolute} 切线直接是切向量本身；{@code relative} 切线按
 * {@link PathPoint#resolve(Vec3)} 的既有语义取 {@code 端点 + (dx,dy,dz)}（相对偏移的基点是端点，
 * 不是世界原点）。
 *
 * <p>与 Bézier 的等价：把 {@code T0 = 3(P1-P0)}、{@code T1 = 3(P3-P2)} 代入即得同一条三次 Bézier
 * （端点 {@code P0/P3}，内部控制点 {@code P1/P2}）。
 *
 * <p>退化：{@code P0=P1} 且 {@code T0=T1=0} 时位置恒为 {@code P0}、导数为零向量、弧长为 0；
 * 其它情况按三次多项式求值，不自动变直线。
 */
public final class HermitePathSpec implements PathSpec {

    /** 首端切线（{@code relative} 时相对 {@code from} 解析）。 */
    private final PathPoint tangentStart;

    /** 末端切线（{@code relative} 时相对 {@code to} 解析）。 */
    private final PathPoint tangentEnd;

    /** 两端切线；任一为 null 抛 {@link IllegalArgumentException}。 */
    public HermitePathSpec(PathPoint tangentStart, PathPoint tangentEnd) {
        if (tangentStart == null || tangentEnd == null) {
            throw new IllegalArgumentException("端点切线不能为 null: tangentStart=" + tangentStart
                    + ", tangentEnd=" + tangentEnd);
        }
        this.tangentStart = tangentStart;
        this.tangentEnd = tangentEnd;
    }

    /**
     * 由 {@code [首端切线, 末端切线]} 构造（注册表工厂用）。
     *
     * @throws IllegalArgumentException 列表为 null、元素个数不是 2、或元素为 null
     */
    public static HermitePathSpec fromTangents(List<PathPoint> tangents) {
        if (tangents == null) {
            throw new IllegalArgumentException("端点切线列表不能为 null");
        }
        if (tangents.size() != 2) {
            throw new IllegalArgumentException("Hermite 需要恰好 2 个端点切线（首端、末端），实际 "
                    + tangents.size() + " 个");
        }
        return new HermitePathSpec(tangents.get(0), tangents.get(1));
    }

    @Override
    public String type() {
        return "hermite";
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
        return new CubicHermiteExpression(from, tangentStart.resolve(from), to, tangentEnd.resolve(to));
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 首端切线定义（未解析）。 */
    public PathPoint tangentStart() {
        return tangentStart;
    }

    /** 末端切线定义（未解析）。 */
    public PathPoint tangentEnd() {
        return tangentEnd;
    }

    private static final class CubicHermiteExpression implements PathExpression {

        private final Vec3 p0;
        private final Vec3 t0;
        private final Vec3 p1;
        private final Vec3 t1;

        CubicHermiteExpression(Vec3 p0, Vec3 t0, Vec3 p1, Vec3 t1) {
            this.p0 = p0;
            this.t0 = t0;
            this.p1 = p1;
            this.t1 = t1;
        }

        @Override
        public String type() {
            return "hermite";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            double u2 = u * u;
            double u3 = u2 * u;
            double h00 = 2.0 * u3 - 3.0 * u2 + 1.0;
            double h10 = u3 - 2.0 * u2 + u;
            double h01 = -2.0 * u3 + 3.0 * u2;
            double h11 = u3 - u2;
            return new Vec3(
                    dx(h00, h10, h01, h11),
                    dy(h00, h10, h01, h11),
                    dz(h00, h10, h01, h11));
        }

        @Override
        public Vec3 derivative(double u) {
            double u2 = u * u;
            double d00 = 6.0 * (u2 - u);
            double d10 = 3.0 * u2 - 4.0 * u + 1.0;
            double d01 = -6.0 * (u2 - u);
            double d11 = 3.0 * u2 - 2.0 * u;
            return new Vec3(
                    dx(d00, d10, d01, d11),
                    dy(d00, d10, d01, d11),
                    dz(d00, d10, d01, d11));
        }

        /** 与 {@link #derivative(double)} 逐项同构的标量求值，不构造向量。 */
        @Override
        public double speed(double u) {
            double u2 = u * u;
            double d00 = 6.0 * (u2 - u);
            double d10 = 3.0 * u2 - 4.0 * u + 1.0;
            double d01 = -6.0 * (u2 - u);
            double d11 = 3.0 * u2 - 2.0 * u;
            double vx = dx(d00, d10, d01, d11);
            double vy = dy(d00, d10, d01, d11);
            double vz = dz(d00, d10, d01, d11);
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            double dd00 = 12.0 * u - 6.0;
            double dd10 = 6.0 * u - 4.0;
            double dd01 = -12.0 * u + 6.0;
            double dd11 = 6.0 * u - 2.0;
            return new Vec3(
                    dx(dd00, dd10, dd01, dd11),
                    dy(dd00, dd10, dd01, dd11),
                    dz(dd00, dd10, dd01, dd11));
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }

        /** {@code c00·P0.x + c10·T0.x + c01·P1.x + c11·T1.x}；位置 / 一阶 / 二阶导共用。 */
        private double dx(double c00, double c10, double c01, double c11) {
            return c00 * p0.x + c10 * t0.x + c01 * p1.x + c11 * t1.x;
        }

        private double dy(double c00, double c10, double c01, double c11) {
            return c00 * p0.y + c10 * t0.y + c01 * p1.y + c11 * t1.y;
        }

        private double dz(double c00, double c10, double c01, double c11) {
            return c00 * p0.z + c10 * t0.z + c01 * p1.z + c11 * t1.z;
        }
    }
}
