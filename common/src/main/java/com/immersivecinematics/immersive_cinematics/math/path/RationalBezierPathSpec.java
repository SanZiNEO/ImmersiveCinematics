package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/**
 * 有理 Bézier 路径：{@code P0 / Pn} 取调用点端点（权重恒 1），内部 1 个控制点 = 二次、
 * 2 个 = 三次（权重取构造值，必须有限且 {@code > 0}）。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#ENDPOINTS}。
 *
 * <p>求值按齐次坐标展开：{@code N(u) = Σ B_i(u) w_i P_i}、{@code D(u) = Σ B_i(u) w_i}、
 * {@code position = N/D}。权重全正且 Bernstein 基在 {@code [0,1]} 上非负、和为 1，
 * 所以 {@code D(u) >= min(w_i) > 0}，位置不会除零。
 *
 * <p>导数用商法则：{@code P' = (N'D - ND')/D²}，
 * {@code P'' = (N''D - ND'')/D² - 2(N'D - ND')D'/D³}；{@link #speed(double)} 与
 * {@link #derivative(double)} 共用同一组私有标量辅助方法（dx/dy/dz），数值一致且零分配。
 * 声明 {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}。
 *
 * <p>退化：所有权重相等时与 {@link BezierPathSpec} 逐点等价（同一个多项式，只是求值路径不同）；
 * 控制点全部重合时为零长曲线，位置恒为 {@code P0}、导数为零。
 */
public final class RationalBezierPathSpec implements PathSpec {

    /** 内部控件点（1 个 = 二次，2 个 = 三次）。 */
    private final List<PathPoint> interiorControlPoints;

    /** 内部权重，与 {@link #interiorControlPoints} 等长，每项有限且 {@code > 0}。 */
    private final double[] interiorWeights;

    /**
     * 内部控件点 1 个（二次）或 2 个（三次），权重数组等长、每项有限且 {@code > 0}；
     * 数量不符 / 元素为 null / 权重非法一律抛 {@link IllegalArgumentException}。
     */
    public RationalBezierPathSpec(List<PathPoint> interiorControlPoints, double[] interiorWeights) {
        if (interiorControlPoints == null) {
            throw new IllegalArgumentException("内部控件点列表不能为 null");
        }
        int count = interiorControlPoints.size();
        if (count != 1 && count != 2) {
            throw new IllegalArgumentException(
                    "有理 Bézier 需要 1 个（二次）或 2 个（三次）内部控件点，实际 " + count + " 个");
        }
        for (int i = 0; i < count; i++) {
            PathPoint point = interiorControlPoints.get(i);
            if (point == null) {
                throw new IllegalArgumentException("内部控件点不能为 null: index=" + i);
            }
        }
        if (interiorWeights == null) {
            throw new IllegalArgumentException("内部权重数组不能为 null");
        }
        if (interiorWeights.length != count) {
            throw new IllegalArgumentException("内部权重数量必须与控件点数量一致: control_points=" + count
                    + ", weights=" + interiorWeights.length);
        }
        for (int i = 0; i < count; i++) {
            double weight = interiorWeights[i];
            if (!Double.isFinite(weight) || weight <= 0.0) {
                throw new IllegalArgumentException("内部权重必须是有限正数: weights[" + i + "]=" + weight);
            }
        }
        this.interiorControlPoints = List.copyOf(interiorControlPoints);
        this.interiorWeights = interiorWeights.clone();
    }

    @Override
    public String type() {
        return "rational_bezier";
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
        int count = interiorControlPoints.size();
        int degree = count + 1;
        double[] x = new double[degree + 1];
        double[] y = new double[degree + 1];
        double[] z = new double[degree + 1];
        double[] weights = new double[degree + 1];
        x[0] = from.x;
        y[0] = from.y;
        z[0] = from.z;
        weights[0] = 1.0;
        for (int i = 0; i < count; i++) {
            Vec3 point = interiorControlPoints.get(i).resolve(from);
            x[i + 1] = point.x;
            y[i + 1] = point.y;
            z[i + 1] = point.z;
            weights[i + 1] = interiorWeights[i];
        }
        x[degree] = to.x;
        y[degree] = to.y;
        z[degree] = to.z;
        weights[degree] = 1.0;
        return new RationalBezierExpression(x, y, z, weights);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 内部控件点（1 个或 2 个，顺序即 {@code P1}、{@code P2}）。 */
    public List<PathPoint> controlPoints() {
        return interiorControlPoints;
    }

    /** 内部权重副本（端点权重恒为 1，不在此数组内）。 */
    public double[] weights() {
        return interiorWeights.clone();
    }

    private static final class RationalBezierExpression implements PathExpression {

        private final int degree;
        private final double[] controlX;
        private final double[] controlY;
        private final double[] controlZ;
        private final double[] weights;
        /** 全 1 数组：把权重和 {@code D(u)} 也走同一条加权求和，避免第二套循环。 */
        private final double[] ones;

        RationalBezierExpression(double[] x, double[] y, double[] z, double[] weights) {
            this.degree = weights.length - 1;
            this.controlX = x;
            this.controlY = y;
            this.controlZ = z;
            this.weights = weights;
            this.ones = new double[weights.length];
            Arrays.fill(ones, 1.0);
        }

        @Override
        public String type() {
            return "rational_bezier";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            double denominator = weighted(0, ones, u);
            return new Vec3(
                    weighted(0, controlX, u) / denominator,
                    weighted(0, controlY, u) / denominator,
                    weighted(0, controlZ, u) / denominator);
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
            return new Vec3(ddx(u), ddy(u), ddz(u));
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }

        /** {@code N'_x D - N_x D'} 除以 {@code D²}。 */
        private double dx(double u) {
            double d0 = weighted(0, ones, u);
            double d1 = weighted(1, ones, u);
            double n0 = weighted(0, controlX, u);
            double n1 = weighted(1, controlX, u);
            return (n1 * d0 - n0 * d1) / (d0 * d0);
        }

        private double dy(double u) {
            double d0 = weighted(0, ones, u);
            double d1 = weighted(1, ones, u);
            double n0 = weighted(0, controlY, u);
            double n1 = weighted(1, controlY, u);
            return (n1 * d0 - n0 * d1) / (d0 * d0);
        }

        private double dz(double u) {
            double d0 = weighted(0, ones, u);
            double d1 = weighted(1, ones, u);
            double n0 = weighted(0, controlZ, u);
            double n1 = weighted(1, controlZ, u);
            return (n1 * d0 - n0 * d1) / (d0 * d0);
        }

        /** 商法则二阶：{@code (N''D - ND'')/D² - 2(N'D - ND')D'/D³}。 */
        private double ddx(double u) {
            double d0 = weighted(0, ones, u);
            double d1 = weighted(1, ones, u);
            double d2 = weighted(2, ones, u);
            double n0 = weighted(0, controlX, u);
            double n1 = weighted(1, controlX, u);
            double n2 = weighted(2, controlX, u);
            double inverse = 1.0 / d0;
            double first = (n1 * d0 - n0 * d1) * inverse * inverse;
            return (n2 * d0 - n0 * d2) * inverse * inverse - 2.0 * first * d1 * inverse;
        }

        private double ddy(double u) {
            double d0 = weighted(0, ones, u);
            double d1 = weighted(1, ones, u);
            double d2 = weighted(2, ones, u);
            double n0 = weighted(0, controlY, u);
            double n1 = weighted(1, controlY, u);
            double n2 = weighted(2, controlY, u);
            double inverse = 1.0 / d0;
            double first = (n1 * d0 - n0 * d1) * inverse * inverse;
            return (n2 * d0 - n0 * d2) * inverse * inverse - 2.0 * first * d1 * inverse;
        }

        private double ddz(double u) {
            double d0 = weighted(0, ones, u);
            double d1 = weighted(1, ones, u);
            double d2 = weighted(2, ones, u);
            double n0 = weighted(0, controlZ, u);
            double n1 = weighted(1, controlZ, u);
            double n2 = weighted(2, controlZ, u);
            double inverse = 1.0 / d0;
            double first = (n1 * d0 - n0 * d1) * inverse * inverse;
            return (n2 * d0 - n0 * d2) * inverse * inverse - 2.0 * first * d1 * inverse;
        }

        /** {@code Σ w_i · B_i^(order)(u) · values[i]}；order 0 / 1 / 2 = 基函数 / 一阶导 / 二阶导。 */
        private double weighted(int order, double[] values, double u) {
            double sum = 0.0;
            for (int i = 0; i <= degree; i++) {
                sum += weights[i] * basis(order, degree, i, u) * values[i];
            }
            return sum;
        }

        /** degree 2 / 3 的 Bernstein 基及其一二阶导，显式系数（不走 Math.pow）。 */
        private static double basis(int order, int degree, int index, double u) {
            double s = 1.0 - u;
            if (degree == 2) {
                return switch (order) {
                    case 0 -> switch (index) {
                        case 0 -> s * s;
                        case 1 -> 2.0 * u * s;
                        default -> u * u;
                    };
                    case 1 -> switch (index) {
                        case 0 -> -2.0 * s;
                        case 1 -> 2.0 - 4.0 * u;
                        default -> 2.0 * u;
                    };
                    default -> switch (index) {
                        case 0 -> 2.0;
                        case 1 -> -4.0;
                        default -> 2.0;
                    };
                };
            }
            return switch (order) {
                case 0 -> switch (index) {
                    case 0 -> s * s * s;
                    case 1 -> 3.0 * u * s * s;
                    case 2 -> 3.0 * u * u * s;
                    default -> u * u * u;
                };
                case 1 -> switch (index) {
                    case 0 -> -3.0 * s * s;
                    case 1 -> 3.0 * s * (1.0 - 3.0 * u);
                    case 2 -> 3.0 * u * (2.0 - 3.0 * u);
                    default -> 3.0 * u * u;
                };
                default -> switch (index) {
                    case 0 -> 6.0 * s;
                    case 1 -> 6.0 * (3.0 * u - 2.0);
                    case 2 -> 6.0 * (1.0 - 3.0 * u);
                    default -> 6.0 * u;
                };
            };
        }
    }
}
