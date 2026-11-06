package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * NURBS 路径：带权重的 B-spline，控制点序列 = {@code [from, interior..., to]}，权重序列 =
 * {@code [1, interiorWeights..., 1]}（端点权重恒为 1），次数 {@code p} 与节点向量由构造给出。
 * 定义域 {@code [knots[p], knots[m]]}（{@code m} = 控制点个数），锚点 {@link PathAnchor#ENDPOINTS}。
 *
 * <p>节点向量缺省与 {@link BSplinePathSpec} 相同的钳制均匀：首末各 {@code p+1} 项取 0 / 1，中间
 * {@code m-p-1} 项为 {@code j/(m-p)}；显式节点必须长度 {@code m+p+1}、非减、有限，且
 * {@code knots[p] < knots[m]}（非钳制也允许，此时曲线不过控制点）。内部权重个数必须等于内部控制点
 * 个数（加上两端恒为 1 的权重后正好 {@code m} 个），每项有限且 {@code > 0}：零 / 负权重会让投影穿过
 * 无穷远，按契约直接拒绝而不是静默夹取。
 *
 * <p>求值按齐次坐标展开（{@link BSplineBasis}）：控制点先升到 {@code (w·x, w·y, w·z, w)}，四组各做
 * 一次 de Boor 得 {@code N_x / N_y / N_z / D}，位置 {@code = N/D}；权重全正、B-spline 基在定义域上
 * 非负且和为 1，所以 {@code D(u) >= min(w_i) > 0}，不会除零。
 * 导数用商法则 {@code P' = (N'D - ND')/D²}、{@code P'' = (N''D - ND'')/D² - 2(N'D - ND')D'/D³}；
 * 一阶导的降次控制点与二阶导的再降次控制点在 {@link #bind} 时一次算好，导数节点向量在构造期算好。
 * {@link #speed(double)} 与 {@link #derivative(double)} 共用同一组私有标量辅助方法（dx/dy/dz），
 * 数值一致且零分配。
 *
 * <p>能力：声明 {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE} 与
 * {@link PathCapability#RATIONAL_WEIGHTS}；{@code degree >= 2} 时另声明
 * {@link PathCapability#KNOT_VECTOR}（一次曲线没有内节点，按能力语义不声明）。
 *
 * <p>退化：所有权重相等时与 {@link BSplinePathSpec} 逐点等价（同一个多项式，只是求值路径不同）；
 * 全部控制点重合时位置恒定、导数为零、弧长为 0，不产生 NaN。
 */
public final class NurbsPathSpec implements PathSpec {

    /** 内部控件点（不含首末端点），顺序即 {@code P1 … P_{m-2}}。 */
    private final List<PathPoint> interiorControlPoints;

    /** 内部权重（端点权重恒为 1，不在本数组内），与内部控件点等长，每项有限且 {@code > 0}。 */
    private final double[] interiorWeights;

    /** 次数 {@code p}，{@code 1 ≤ p ≤ m-1}。 */
    private final int degree;

    /** 节点向量，长度 {@code m+p+1}，非减且 {@code knots[degree] < knots[m]}。 */
    private final double[] knots;

    /** 定义域 {@code [knots[degree], knots[m]]}。 */
    private final PathDomain domain;

    /** 是否声明 {@link PathCapability#KNOT_VECTOR}（{@code degree >= 2}）。 */
    private final boolean knotVector;

    /** 一阶导曲线的节点向量（次数 {@code degree-1}），构造期算好，{@link #bind} 只拼控制点。 */
    private final double[] firstKnots;

    /** 二阶导曲线的节点向量（次数 {@code degree-2}）；{@code degree < 2} 时 {@code null}。 */
    private final double[] secondKnots;

    /**
     * @param interiorControlPoints 内部控制点（首末端点由 {@link #bind(Vec3, Vec3)} 的关键帧给出），非 null、无 null 元素
     * @param interiorWeights       内部权重，长度必须等于内部控制点个数，每项有限且 {@code > 0}
     * @param degree                次数，必须落在 {@code 1 ~ m-1}（{@code m = interior.size()+2}）
     * @param knots                 节点向量；{@code null} = 钳制均匀，否则长度 {@code m+degree+1}、非减、有限
     * @throws IllegalArgumentException 控制点 / 权重 / 次数 / 节点不合法
     */
    public NurbsPathSpec(List<PathPoint> interiorControlPoints, double[] interiorWeights, int degree, double[] knots) {
        if (interiorControlPoints == null) {
            throw new IllegalArgumentException("内部控制点列表不能为 null");
        }
        int interiorCount = interiorControlPoints.size();
        int controlCount = interiorCount + 2;
        if (controlCount < 2) {
            throw new IllegalArgumentException("NURBS 控制点（含首末端点）至少 2 个，实际 " + controlCount + " 个");
        }
        if (degree < 1 || degree > controlCount - 1) {
            throw new IllegalArgumentException("NURBS 次数必须落在 1 ~ " + (controlCount - 1)
                    + "（控制点数 " + controlCount + "），实际 " + degree);
        }
        for (int i = 0; i < interiorCount; i++) {
            if (interiorControlPoints.get(i) == null) {
                throw new IllegalArgumentException("内部控制点不能为 null: index=" + i);
            }
        }
        if (interiorWeights == null) {
            throw new IllegalArgumentException("内部权重数组不能为 null（权重全 1 时传空数组）");
        }
        if (interiorWeights.length != interiorCount) {
            throw new IllegalArgumentException("内部权重个数必须等于内部控制点个数: control_points=" + interiorCount
                    + ", weights=" + interiorWeights.length + "（端点权重恒为 1，权重序列共 " + controlCount + " 项）");
        }
        for (int i = 0; i < interiorCount; i++) {
            double weight = interiorWeights[i];
            if (!Double.isFinite(weight) || weight <= 0.0) {
                throw new IllegalArgumentException("内部权重必须是有限正数: weights[" + i + "]=" + weight);
            }
        }
        this.interiorControlPoints = List.copyOf(interiorControlPoints);
        this.interiorWeights = interiorWeights.clone();
        this.degree = degree;
        this.knots = knots == null
                ? uniformClampedKnots(controlCount, degree)
                : checkedKnots(knots, controlCount, degree);
        this.domain = new PathDomain(this.knots[degree], this.knots[controlCount]);
        this.knotVector = degree >= 2;
        this.firstKnots = BSplineBasis.derivativeKnots(this.knots);
        this.secondKnots = degree >= 2 ? BSplineBasis.derivativeKnots(firstKnots) : null;
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
        return "nurbs";
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
        // 齐次控制点 (w·x, w·y, w·z, w)：端点权重恒为 1，内部权重取构造值。
        double[] homogeneousX = new double[controlCount];
        double[] homogeneousY = new double[controlCount];
        double[] homogeneousZ = new double[controlCount];
        double[] homogeneousW = new double[controlCount];
        homogeneousX[0] = from.x;
        homogeneousY[0] = from.y;
        homogeneousZ[0] = from.z;
        homogeneousW[0] = 1.0;
        for (int i = 0; i < interiorCount; i++) {
            Vec3 point = interiorControlPoints.get(i).resolve(from);
            double weight = interiorWeights[i];
            homogeneousX[i + 1] = weight * point.x;
            homogeneousY[i + 1] = weight * point.y;
            homogeneousZ[i + 1] = weight * point.z;
            homogeneousW[i + 1] = weight;
        }
        homogeneousX[controlCount - 1] = to.x;
        homogeneousY[controlCount - 1] = to.y;
        homogeneousZ[controlCount - 1] = to.z;
        homogeneousW[controlCount - 1] = 1.0;
        return new NurbsExpression(degree, knots, domain, knotVector, firstKnots, secondKnots,
                homogeneousX, homogeneousY, homogeneousZ, homogeneousW);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE
                || capability == PathCapability.RATIONAL_WEIGHTS
                || (knotVector && capability == PathCapability.KNOT_VECTOR);
    }

    /** 内部控制点（不含首末端点）。 */
    public List<PathPoint> controlPoints() {
        return interiorControlPoints;
    }

    /** 内部权重副本（端点权重恒为 1，不在此数组内）。 */
    public double[] weights() {
        return interiorWeights.clone();
    }

    /** 次数 {@code p}。 */
    public int degree() {
        return degree;
    }

    /** 节点向量副本（长度 {@code m+p+1}）。 */
    public double[] knots() {
        return knots.clone();
    }

    private static final class NurbsExpression implements PathExpression {

        private final int degree;
        private final double[] knots;
        private final PathDomain domain;
        private final boolean knotVector;
        /** 齐次位置控制点 {@code (w·x, w·y, w·z, w)}，长度 {@code m}。 */
        private final double[] homX;
        private final double[] homY;
        private final double[] homZ;
        private final double[] homW;
        /** 一阶导：齐次控制点降次后的结果（{@code degree-1}），节点向量构造期已算好。 */
        private final int firstDegree;
        private final double[] firstKnots;
        private final double[] firstHomX;
        private final double[] firstHomY;
        private final double[] firstHomZ;
        private final double[] firstHomW;
        /** 二阶导：再降一次（{@code degree-2}）；{@code degree < 2} 时全为 null、二阶导恒零向量。 */
        private final int secondDegree;
        private final double[] secondKnots;
        private final double[] secondHomX;
        private final double[] secondHomY;
        private final double[] secondHomZ;
        private final double[] secondHomW;

        NurbsExpression(int degree, double[] knots, PathDomain domain, boolean knotVector,
                        double[] firstKnots, double[] secondKnots,
                        double[] homX, double[] homY, double[] homZ, double[] homW) {
            this.degree = degree;
            this.knots = knots;
            this.domain = domain;
            this.knotVector = knotVector;
            this.homX = homX;
            this.homY = homY;
            this.homZ = homZ;
            this.homW = homW;
            this.firstDegree = degree - 1;
            this.firstKnots = firstKnots;
            this.firstHomX = BSplineBasis.derivativeControlPoints(homX, degree, knots);
            this.firstHomY = BSplineBasis.derivativeControlPoints(homY, degree, knots);
            this.firstHomZ = BSplineBasis.derivativeControlPoints(homZ, degree, knots);
            this.firstHomW = BSplineBasis.derivativeControlPoints(homW, degree, knots);
            if (degree >= 2) {
                this.secondDegree = degree - 2;
                this.secondKnots = secondKnots;
                this.secondHomX = BSplineBasis.derivativeControlPoints(firstHomX, firstDegree, firstKnots);
                this.secondHomY = BSplineBasis.derivativeControlPoints(firstHomY, firstDegree, firstKnots);
                this.secondHomZ = BSplineBasis.derivativeControlPoints(firstHomZ, firstDegree, firstKnots);
                this.secondHomW = BSplineBasis.derivativeControlPoints(firstHomW, firstDegree, firstKnots);
            } else {
                this.secondDegree = -1;
                this.secondKnots = null;
                this.secondHomX = null;
                this.secondHomY = null;
                this.secondHomZ = null;
                this.secondHomW = null;
            }
        }

        @Override
        public String type() {
            return "nurbs";
        }

        @Override
        public PathDomain domain() {
            return domain;
        }

        @Override
        public Vec3 position(double u) {
            double denominator = BSplineBasis.deBoor(homW, degree, knots, u);
            return new Vec3(
                    BSplineBasis.deBoor(homX, degree, knots, u) / denominator,
                    BSplineBasis.deBoor(homY, degree, knots, u) / denominator,
                    BSplineBasis.deBoor(homZ, degree, knots, u) / denominator);
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
            return new Vec3(ddx(u), ddy(u), ddz(u));
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE
                    || capability == PathCapability.RATIONAL_WEIGHTS
                    || (knotVector && capability == PathCapability.KNOT_VECTOR);
        }

        /** {@code (N'_x D - N_x D') / D²}。 */
        private double dx(double u) {
            double d0 = BSplineBasis.deBoor(homW, degree, knots, u);
            double d1 = BSplineBasis.deBoor(firstHomW, firstDegree, firstKnots, u);
            double n0 = BSplineBasis.deBoor(homX, degree, knots, u);
            double n1 = BSplineBasis.deBoor(firstHomX, firstDegree, firstKnots, u);
            return (n1 * d0 - n0 * d1) / (d0 * d0);
        }

        private double dy(double u) {
            double d0 = BSplineBasis.deBoor(homW, degree, knots, u);
            double d1 = BSplineBasis.deBoor(firstHomW, firstDegree, firstKnots, u);
            double n0 = BSplineBasis.deBoor(homY, degree, knots, u);
            double n1 = BSplineBasis.deBoor(firstHomY, firstDegree, firstKnots, u);
            return (n1 * d0 - n0 * d1) / (d0 * d0);
        }

        private double dz(double u) {
            double d0 = BSplineBasis.deBoor(homW, degree, knots, u);
            double d1 = BSplineBasis.deBoor(firstHomW, firstDegree, firstKnots, u);
            double n0 = BSplineBasis.deBoor(homZ, degree, knots, u);
            double n1 = BSplineBasis.deBoor(firstHomZ, firstDegree, firstKnots, u);
            return (n1 * d0 - n0 * d1) / (d0 * d0);
        }

        /** 商法则二阶：{@code (N''D - ND'')/D² - 2(N'D - ND')D'/D³}。 */
        private double ddx(double u) {
            double d0 = BSplineBasis.deBoor(homW, degree, knots, u);
            double d1 = BSplineBasis.deBoor(firstHomW, firstDegree, firstKnots, u);
            double d2 = BSplineBasis.deBoor(secondHomW, secondDegree, secondKnots, u);
            double n0 = BSplineBasis.deBoor(homX, degree, knots, u);
            double n1 = BSplineBasis.deBoor(firstHomX, firstDegree, firstKnots, u);
            double n2 = BSplineBasis.deBoor(secondHomX, secondDegree, secondKnots, u);
            double inverse = 1.0 / d0;
            double first = (n1 * d0 - n0 * d1) * inverse * inverse;
            return (n2 * d0 - n0 * d2) * inverse * inverse - 2.0 * first * d1 * inverse;
        }

        private double ddy(double u) {
            double d0 = BSplineBasis.deBoor(homW, degree, knots, u);
            double d1 = BSplineBasis.deBoor(firstHomW, firstDegree, firstKnots, u);
            double d2 = BSplineBasis.deBoor(secondHomW, secondDegree, secondKnots, u);
            double n0 = BSplineBasis.deBoor(homY, degree, knots, u);
            double n1 = BSplineBasis.deBoor(firstHomY, firstDegree, firstKnots, u);
            double n2 = BSplineBasis.deBoor(secondHomY, secondDegree, secondKnots, u);
            double inverse = 1.0 / d0;
            double first = (n1 * d0 - n0 * d1) * inverse * inverse;
            return (n2 * d0 - n0 * d2) * inverse * inverse - 2.0 * first * d1 * inverse;
        }

        private double ddz(double u) {
            double d0 = BSplineBasis.deBoor(homW, degree, knots, u);
            double d1 = BSplineBasis.deBoor(firstHomW, firstDegree, firstKnots, u);
            double d2 = BSplineBasis.deBoor(secondHomW, secondDegree, secondKnots, u);
            double n0 = BSplineBasis.deBoor(homZ, degree, knots, u);
            double n1 = BSplineBasis.deBoor(firstHomZ, firstDegree, firstKnots, u);
            double n2 = BSplineBasis.deBoor(secondHomZ, secondDegree, secondKnots, u);
            double inverse = 1.0 / d0;
            double first = (n1 * d0 - n0 * d1) * inverse * inverse;
            return (n2 * d0 - n0 * d2) * inverse * inverse - 2.0 * first * d1 * inverse;
        }
    }
}
