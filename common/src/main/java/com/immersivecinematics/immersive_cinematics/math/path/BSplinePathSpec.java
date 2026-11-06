package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * B-spline 路径：控制点序列 = {@code [from, interior..., to]}，次数 {@code p} 与节点向量由构造给出。
 * 定义域 {@code [knots[p], knots[m]]}（{@code m} = 控制点个数），锚点 {@link PathAnchor#ENDPOINTS}。
 *
 * <p>节点向量缺省为钳制均匀：首末各 {@code p+1} 项取 0 / 1，中间 {@code m-p-1} 项为
 * {@code j/(m-p)}；钳制端点使曲线在定义域两端取首末控制点。显式节点必须长度
 * {@code m+p+1}、非减、有限，且 {@code knots[p] < knots[m]}（非钳制也允许，此时曲线不过控制点）。
 *
 * <p>求值走 de Boor（{@link BSplineBasis}）：位置直接用原控制点，一阶导用
 * {@link BSplineBasis#derivativeControlPoints} 降次后的控制点与 {@link BSplineBasis#derivativeKnots}
 * 剪过的节点向量，二阶导再降一次；{@link #speed(double)} 与 {@link #derivative(double)} 共用同一组
 * 私有标量辅助方法（dx/dy/dz），数值一致且零分配。
 *
 * <p>能力：声明 {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}；{@code degree >= 2} 时另声明
 * {@link PathCapability#KNOT_VECTOR}（一次曲线没有内节点，按能力语义不声明）。
 *
 * <p>退化：全部控制点重合时位置恒定、导数为零、弧长为 0；重复节点造成的零分母在导数控制点里取 0，
 * 不产生 NaN。
 */
public final class BSplinePathSpec implements PathSpec {

    /** 内部控件点（不含首末端点），顺序即 {@code P1 … P_{m-2}}。 */
    private final List<PathPoint> interiorControlPoints;

    /** 次数 {@code p}，{@code 1 ≤ p ≤ m-1}。 */
    private final int degree;

    /** 节点向量，长度 {@code m+p+1}，非减且 {@code knots[degree] < knots[m]}。 */
    private final double[] knots;

    /** 定义域 {@code [knots[degree], knots[m]]}。 */
    private final PathDomain domain;

    /** 是否声明 {@link PathCapability#KNOT_VECTOR}（{@code degree >= 2}）。 */
    private final boolean knotVector;

    /**
     * @param interiorControlPoints 内部控制点（首末端点由 {@link #bind(Vec3, Vec3)} 的关键帧给出），非 null、无 null 元素
     * @param degree                次数，必须落在 {@code 1 ~ m-1}（{@code m = interior.size()+2}）
     * @param knots                 节点向量；{@code null} = 钳制均匀，否则长度 {@code m+degree+1}、非减、有限
     * @throws IllegalArgumentException 控制点 / 次数 / 节点不合法
     */
    public BSplinePathSpec(List<PathPoint> interiorControlPoints, int degree, double[] knots) {
        if (interiorControlPoints == null) {
            throw new IllegalArgumentException("内部控制点列表不能为 null");
        }
        int controlCount = interiorControlPoints.size() + 2;
        if (controlCount < 2) {
            throw new IllegalArgumentException("B-spline 控制点（含首末端点）至少 2 个，实际 " + controlCount + " 个");
        }
        if (degree < 1 || degree > controlCount - 1) {
            throw new IllegalArgumentException("B-spline 次数必须落在 1 ~ " + (controlCount - 1)
                    + "（控制点数 " + controlCount + "），实际 " + degree);
        }
        for (int i = 0; i < interiorControlPoints.size(); i++) {
            if (interiorControlPoints.get(i) == null) {
                throw new IllegalArgumentException("内部控制点不能为 null: index=" + i);
            }
        }
        this.interiorControlPoints = List.copyOf(interiorControlPoints);
        this.degree = degree;
        this.knots = knots == null
                ? uniformClampedKnots(controlCount, degree)
                : checkedKnots(knots, controlCount, degree);
        this.domain = new PathDomain(this.knots[degree], this.knots[controlCount]);
        this.knotVector = degree >= 2;
    }

    /** 钳制均匀节点：首 {@code p+1} 项 0、末 {@code p+1} 项 1、中间 {@code j/(m-p)}。 */
    private static double[] uniformClampedKnots(int controlCount, int degree) {
        double[] knots = new double[controlCount + degree + 1];
        for (int i = 0; i < knots.length; i++) {
            if (i <= degree) {
                knots[i] = 0.0;
            } else if (i >= controlCount) {
                knots[i] = 1.0;
            } else {
                knots[i] = (i - degree) / (double) (controlCount - degree);
            }
        }
        return knots;
    }

    /** 校验显式节点向量并复制：长度匹配、有限、非减、定义域跨度为正。 */
    private static double[] checkedKnots(double[] knots, int controlCount, int degree) {
        int expected = controlCount + degree + 1;
        if (knots.length != expected) {
            throw new IllegalArgumentException("节点向量长度必须为 控制点数+次数+1 = " + expected
                    + "，实际 " + knots.length);
        }
        for (int i = 0; i < knots.length; i++) {
            if (!Double.isFinite(knots[i])) {
                throw new IllegalArgumentException("节点必须有限: knots[" + i + "]=" + knots[i]);
            }
            if (i > 0 && knots[i] < knots[i - 1]) {
                throw new IllegalArgumentException("节点向量必须非减: knots[" + (i - 1) + "]=" + knots[i - 1]
                        + " > knots[" + i + "]=" + knots[i]);
            }
        }
        if (!(knots[degree] < knots[controlCount])) {
            throw new IllegalArgumentException("定义域跨度必须为正: knots[" + degree + "]=" + knots[degree]
                    + ", knots[" + controlCount + "]=" + knots[controlCount]);
        }
        return knots.clone();
    }

    @Override
    public String type() {
        return "bspline";
    }

    @Override
    public PathDomain domain() {
        return domain;
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
        int interiorCount = interiorControlPoints.size();
        int controlCount = interiorCount + 2;
        double[] x = new double[controlCount];
        double[] y = new double[controlCount];
        double[] z = new double[controlCount];
        x[0] = from.x;
        y[0] = from.y;
        z[0] = from.z;
        for (int i = 0; i < interiorCount; i++) {
            Vec3 point = interiorControlPoints.get(i).resolve(from);
            x[i + 1] = point.x;
            y[i + 1] = point.y;
            z[i + 1] = point.z;
        }
        x[controlCount - 1] = to.x;
        y[controlCount - 1] = to.y;
        z[controlCount - 1] = to.z;
        return new BSplineExpression(degree, knots, domain, knotVector, x, y, z);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE
                || (knotVector && capability == PathCapability.KNOT_VECTOR);
    }

    /** 内部控制点（不含首末端点）。 */
    public List<PathPoint> controlPoints() {
        return interiorControlPoints;
    }

    /** 次数 {@code p}。 */
    public int degree() {
        return degree;
    }

    /** 节点向量副本（长度 {@code m+p+1}）。 */
    public double[] knots() {
        return knots.clone();
    }

    private static final class BSplineExpression implements PathExpression {

        private final int degree;
        private final double[] knots;
        private final PathDomain domain;
        private final boolean knotVector;
        /** 位置控制点：三组标量，长度 {@code m}。 */
        private final double[] controlX;
        private final double[] controlY;
        private final double[] controlZ;
        /** 一阶导：降次后的控制点与节点向量（{@code degree-1}）。 */
        private final int firstDegree;
        private final double[] firstKnots;
        private final double[] firstX;
        private final double[] firstY;
        private final double[] firstZ;
        /** 二阶导：再降一次（{@code degree-2}）；{@code degree < 2} 时全为 null、二阶导恒零向量。 */
        private final int secondDegree;
        private final double[] secondKnots;
        private final double[] secondX;
        private final double[] secondY;
        private final double[] secondZ;

        BSplineExpression(int degree, double[] knots, PathDomain domain, boolean knotVector,
                          double[] x, double[] y, double[] z) {
            this.degree = degree;
            this.knots = knots;
            this.domain = domain;
            this.knotVector = knotVector;
            this.controlX = x;
            this.controlY = y;
            this.controlZ = z;
            this.firstDegree = degree - 1;
            this.firstKnots = BSplineBasis.derivativeKnots(knots);
            this.firstX = BSplineBasis.derivativeControlPoints(x, degree, knots);
            this.firstY = BSplineBasis.derivativeControlPoints(y, degree, knots);
            this.firstZ = BSplineBasis.derivativeControlPoints(z, degree, knots);
            if (degree >= 2) {
                this.secondDegree = degree - 2;
                this.secondKnots = BSplineBasis.derivativeKnots(firstKnots);
                this.secondX = BSplineBasis.derivativeControlPoints(firstX, firstDegree, firstKnots);
                this.secondY = BSplineBasis.derivativeControlPoints(firstY, firstDegree, firstKnots);
                this.secondZ = BSplineBasis.derivativeControlPoints(firstZ, firstDegree, firstKnots);
            } else {
                this.secondDegree = -1;
                this.secondKnots = null;
                this.secondX = null;
                this.secondY = null;
                this.secondZ = null;
            }
        }

        @Override
        public String type() {
            return "bspline";
        }

        @Override
        public PathDomain domain() {
            return domain;
        }

        @Override
        public Vec3 position(double u) {
            return new Vec3(
                    BSplineBasis.deBoor(controlX, degree, knots, u),
                    BSplineBasis.deBoor(controlY, degree, knots, u),
                    BSplineBasis.deBoor(controlZ, degree, knots, u));
        }

        @Override
        public Vec3 derivative(double u) {
            return new Vec3(dx(u), dy(u), dz(u));
        }

        /** 与 {@link #derivative(double)} 同一组标量辅助方法，不分配任何对象。 */
        @Override
        public double speed(double u) {
            double vx = dx(u);
            double vy = dy(u);
            double vz = dz(u);
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            if (secondDegree < 0) {
                return Vec3.ZERO;
            }
            return new Vec3(
                    BSplineBasis.deBoor(secondX, secondDegree, secondKnots, u),
                    BSplineBasis.deBoor(secondY, secondDegree, secondKnots, u),
                    BSplineBasis.deBoor(secondZ, secondDegree, secondKnots, u));
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE
                    || (knotVector && capability == PathCapability.KNOT_VECTOR);
        }

        private double dx(double u) {
            return BSplineBasis.deBoor(firstX, firstDegree, firstKnots, u);
        }

        private double dy(double u) {
            return BSplineBasis.deBoor(firstY, firstDegree, firstKnots, u);
        }

        private double dz(double u) {
            return BSplineBasis.deBoor(firstZ, firstDegree, firstKnots, u);
        }
    }
}
