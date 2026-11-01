package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 三次 Bézier 路径：{@code P0 / P3} 取调用点端点，{@code P1 / P2} 取两个内部控制点。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#ENDPOINTS}；控制点恰好 2 个，否则构造失败。
 * 退化控制点仍按三次多项式求值，不自动变直线；{@code P0=P3} 且 {@code P1=P2=P0} 时为零长。
 * 声明 {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}。
 */
public final class BezierPathSpec implements PathSpec {

    private final List<PathPoint> controlPoints;

    /** 恰好两个内部控制点；数量不符或元素为 null 抛 {@link IllegalArgumentException}。 */
    public BezierPathSpec(List<PathPoint> controlPoints) {
        if (controlPoints == null) {
            throw new IllegalArgumentException("控制点列表不能为 null");
        }
        if (controlPoints.size() != 2) {
            throw new IllegalArgumentException(
                    "三次 Bézier 需要恰好 2 个内部控制点，实际 " + controlPoints.size() + " 个");
        }
        PathPoint first = controlPoints.get(0);
        PathPoint second = controlPoints.get(1);
        if (first == null || second == null) {
            throw new IllegalArgumentException(
                    "控制点不能为 null: first=" + first + ", second=" + second);
        }
        this.controlPoints = List.of(first, second);
    }

    @Override
    public String type() {
        return "bezier";
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
        return new CubicBezierExpression(
                from,
                controlPoints.get(0).resolve(from),
                controlPoints.get(1).resolve(from),
                to);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 内部控制点（固定 2 个，顺序 P1、P2）。 */
    public List<PathPoint> controlPoints() {
        return controlPoints;
    }

    /**
     * 二次 Bézier 升阶为三次：{@code Q1 = P0 + (2/3)(P1-P0)}、{@code Q2 = P2 + (2/3)(P1-P2)}。
     * 返回内部点存为绝对坐标的定义；以 {@code p0} / {@code p2} 绑定时与二次曲线逐点等价。
     */
    public static PathSpec quadratic(Vec3 p0, Vec3 p1, Vec3 p2) {
        if (p0 == null || p1 == null || p2 == null) {
            throw new IllegalArgumentException(
                    "二次 Bézier 控制点不能为 null: p0=" + p0 + ", p1=" + p1 + ", p2=" + p2);
        }
        double k = 2.0 / 3.0;
        Vec3 q1 = p0.add(p1.subtract(p0).scale(k));
        Vec3 q2 = p2.add(p1.subtract(p2).scale(k));
        return new BezierPathSpec(List.of(
                PathPoint.absolute(q1.x, q1.y, q1.z),
                PathPoint.absolute(q2.x, q2.y, q2.z)));
    }

    private static final class CubicBezierExpression implements PathExpression {

        private final Vec3 p0;
        private final Vec3 d1;
        private final Vec3 d2;
        private final Vec3 d3;
        private final Vec3 b;
        private final Vec3 c;
        private final boolean degenerate;

        CubicBezierExpression(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
            this.p0 = p0;
            this.d1 = p1.subtract(p0);
            this.d2 = p2.subtract(p1);
            this.d3 = p3.subtract(p2);
            this.b = d2.subtract(d1);
            this.c = d3.subtract(d2.scale(2.0)).add(d1);
            this.degenerate = same(p0, p3) && same(p0, p1) && same(p1, p2);
        }

        private static boolean same(Vec3 a, Vec3 b) {
            return a.x == b.x && a.y == b.y && a.z == b.z;
        }

        @Override
        public String type() {
            return "bezier";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            if (degenerate) {
                return p0;
            }
            double u2 = u * u;
            double u3 = u2 * u;
            return new Vec3(
                    p0.x + 3.0 * d1.x * u + 3.0 * b.x * u2 + c.x * u3,
                    p0.y + 3.0 * d1.y * u + 3.0 * b.y * u2 + c.y * u3,
                    p0.z + 3.0 * d1.z * u + 3.0 * b.z * u2 + c.z * u3);
        }

        @Override
        public Vec3 derivative(double u) {
            if (degenerate) {
                return Vec3.ZERO;
            }
            double u2 = u * u;
            return new Vec3(
                    3.0 * d1.x + 6.0 * b.x * u + 3.0 * c.x * u2,
                    3.0 * d1.y + 6.0 * b.y * u + 3.0 * c.y * u2,
                    3.0 * d1.z + 6.0 * b.z * u + 3.0 * c.z * u2);
        }

        /** 与 {@link #derivative(double)} 逐项同构的标量求值，不构造向量。 */
        @Override
        public double speed(double u) {
            if (degenerate) {
                return 0.0;
            }
            double u2 = u * u;
            double dx = 3.0 * d1.x + 6.0 * b.x * u + 3.0 * c.x * u2;
            double dy = 3.0 * d1.y + 6.0 * b.y * u + 3.0 * c.y * u2;
            double dz = 3.0 * d1.z + 6.0 * b.z * u + 3.0 * c.z * u2;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            if (degenerate) {
                return Vec3.ZERO;
            }
            return new Vec3(
                    6.0 * b.x + 6.0 * c.x * u,
                    6.0 * b.y + 6.0 * c.y * u,
                    6.0 * b.z + 6.0 * c.z * u);
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }
    }
}
