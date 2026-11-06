package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import net.minecraft.world.phys.Vec3;

/**
 * 回旋线（clothoid / Euler 螺旋）：曲率随弧长线性变化，用于直线↔圆弧、圆弧↔圆弧的 G2 过渡。
 *
 * <p>几何完全由参数决定（锚点 {@link PathAnchor#START}：只有段起点 {@code from} 参与，
 * {@code to} 被忽略）。绑定后取
 * {@code T0 = normalize(start_direction)}、{@code N0 = normalize(normal × T0)}，
 * 曲率剖面 {@code κ(s) = κ0 + a·s}、{@code a = (κ1-κ0)/L}，切线角
 * {@code θ(s) = κ0·s + a·s²/2}；位置在局部坐标系里积分
 * {@code X(s) = ∫₀^s cos θ dσ}、{@code Y(s) = ∫₀^s sin θ dσ}，再映射为
 * {@code from + X·T0 + Y·N0}。定义域 {@link PathDomain#UNIT}，{@code s = u·L}。
 *
 * <p>求值分三支：
 * <ul>
 *   <li>{@code a == 0} 且 {@code κ0 == 0}：直线 {@code X = s, Y = 0}；</li>
 *   <li>{@code a == 0} 且 {@code κ0 != 0}：圆弧 {@code X = sin(κ0 s)/κ0}、{@code Y = (1-cos(κ0 s))/κ0}；</li>
 *   <li>{@code a != 0}：配方 {@code θ = (a/2)(s+u0)² - c}（{@code u0 = κ0/a}、{@code c = κ0²/(2a)}），
 *       令 {@code t(s) = (s+u0)·sqrt(|a|/π)}，则
 *       {@code X = sqrt(π/|a|)·[cos c·ΔC + sign(a)·sin c·ΔS]}、
 *       {@code Y = sqrt(π/|a|)·[sign(a)·cos c·ΔS - sin c·ΔC]}，
 *       {@code ΔC = C(t2)-C(t1)}、{@code ΔS = S(t2)-S(t1)}（{@link Fresnel}）。</li>
 * </ul>
 *
 * <p>{@code a < 0} 的符号：{@code θ = -(|a|/2)(s+u0)² - c}，代入
 * {@code cos θ = cos((π/2)t² + c)}、{@code sin θ = -sin((π/2)t² + c)} 即得上面的 {@code sign(a)}
 * 位置（{@code ΔS} 项在 X 上带 {@code sign(a)}、在 Y 上落在 {@code cos c·ΔS} 上）。
 *
 * <p>近圆弧保护：{@code |a|·L² <= 1e-6} 时改用中点曲率 {@code κ̄ = (κ0+κ1)/2} 的圆弧闭式——
 * 该区间里 Fresnel 配方的相位 {@code c = κ0²/(2a)} 趋于无穷，其 ulp 会吞掉有效位
 * （{@code κ0=1, Δκ=1e-12} 时误差可达 1e-4），而圆弧近似的相对偏差只有 {@code ≤ |a|L²/16 ≤ 6e-8}。
 * 阈值以上 Fresnel 配方的相位舍入误差 {@code ≈ eps·|c|} 已可忽略。
 *
 * <p>解析量：{@link #speed(double)} 恒为 {@code L}（弧长参数化，零分配）；
 * 声明 {@link PathCapability#ANALYTIC_ARC_LENGTH}（{@code arcLength = L·(u1-u0)}）与
 * {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}（{@code P'' = L²·κ(s)·(-sinθ·T0 + cosθ·N0)}）。
 *
 * <p>非法参数（长度非正/非有限、曲率非有限、方向向量零或非有限、法向与起点方向平行）在构造器内抛
 * {@link IllegalArgumentException}，不静默回落直线。
 */
public final class ClothoidPathSpec implements PathSpec {

    /** 缺省起点切线方向：世界 +X。 */
    public static final Vec3 DEFAULT_START_DIRECTION = new Vec3(1.0, 0.0, 0.0);

    /** 缺省过渡平面法向：世界 +Y。 */
    public static final Vec3 DEFAULT_NORMAL = new Vec3(0.0, 1.0, 0.0);

    /** 方向与法向的夹角正弦下限（与 {@link ConicSegmentBuilder} 的退化阈值同口径）。 */
    private static final double PARALLEL_EPSILON = 1e-9;

    /** 近圆弧切换阈值：{@code |a|·L²} 低于它改用中点曲率圆弧（见类注释）。 */
    private static final double NEAR_CIRCULAR = 1e-6;

    private final double startCurvature;
    private final double endCurvature;
    private final double length;
    private final Vec3 startDirection;
    private final Vec3 normal;

    /**
     * 回旋线定义。
     *
     * @param startCurvature 起点曲率（1/方块，有限；负值 = 反向弯）
     * @param endCurvature   终点曲率（1/方块，有限）
     * @param length         弧长（方块，有限正数）
     * @param startDirection 起点切线方向（有限非零）
     * @param normal         过渡平面法向（有限非零，不得与 {@code startDirection} 平行）
     */
    public ClothoidPathSpec(double startCurvature, double endCurvature, double length,
                            Vec3 startDirection, Vec3 normal) {
        if (!Double.isFinite(startCurvature)) {
            throw new IllegalArgumentException("起点曲率必须是有限值: start_curvature=" + startCurvature);
        }
        if (!Double.isFinite(endCurvature)) {
            throw new IllegalArgumentException("终点曲率必须是有限值: end_curvature=" + endCurvature);
        }
        if (!Double.isFinite(length) || length <= 0.0) {
            throw new IllegalArgumentException("长度必须是正的有限值: length=" + length);
        }
        Vec3 unitDirection = unit(startDirection, "起点切线方向");
        Vec3 unitNormal = unit(normal, "过渡平面法向");
        double crossLength = PathMath.length(PathMath.cross(unitNormal, unitDirection));
        if (crossLength < PARALLEL_EPSILON) {
            throw new IllegalArgumentException("过渡平面法向不能与起点切线方向平行（归一化叉积模长 "
                    + crossLength + " < " + PARALLEL_EPSILON + "，坐标系退化）: start_direction=" + startDirection
                    + ", normal=" + normal);
        }
        this.startCurvature = startCurvature;
        this.endCurvature = endCurvature;
        this.length = length;
        this.startDirection = startDirection;
        this.normal = normal;
    }

    @Override
    public String type() {
        return "clothoid";
    }

    @Override
    public PathDomain domain() {
        return PathDomain.UNIT;
    }

    @Override
    public PathAnchor anchor() {
        return PathAnchor.START;
    }

    /** 几何由参数决定（START 锚点）：{@code from} 是曲线起点，{@code to} 被忽略。 */
    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (from == null || !PathMath.isFinite(from)) {
            throw new IllegalArgumentException("段起点必须是有限坐标: from=" + from);
        }
        Vec3 tangent = unit(startDirection, "起点切线方向");
        Vec3 left = unit(PathMath.cross(normal, tangent), "过渡平面法向 × 起点切线方向");
        return new ClothoidExpression(from, tangent, left, length, startCurvature, endCurvature);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_ARC_LENGTH
                || capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 起点曲率（1/方块）。 */
    public double startCurvature() {
        return startCurvature;
    }

    /** 终点曲率（1/方块）。 */
    public double endCurvature() {
        return endCurvature;
    }

    /** 弧长（方块）。 */
    public double length() {
        return length;
    }

    /** 起点切线方向（按构造值保存，未归一化）。 */
    public Vec3 startDirection() {
        return startDirection;
    }

    /** 过渡平面法向（按构造值保存，未归一化）。 */
    public Vec3 normal() {
        return normal;
    }

    /** 归一化非零有限向量；非法值抛 {@link IllegalArgumentException}。 */
    private static Vec3 unit(Vec3 value, String name) {
        if (value == null || !PathMath.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限向量: " + name + "=" + value);
        }
        double magnitude = PathMath.length(value);
        if (!Double.isFinite(magnitude)) {
            throw new IllegalArgumentException(name + " 的模长溢出（分量过大）: " + name + "=" + value);
        }
        if (magnitude == 0.0) {
            throw new IllegalArgumentException(name + " 不能是零向量: " + name + "=" + value);
        }
        return value.scale(1.0 / magnitude);
    }

    /** 绑定后的回旋线求值器：局部坐标系 + 曲率剖面全部预计算。 */
    private static final class ClothoidExpression implements PathExpression, AnalyticArcLength {

        private final Vec3 origin;
        private final Vec3 tangent;
        private final Vec3 left;
        private final double length;
        private final double startCurvature;
        /** 曲率变化率 {@code (κ1-κ0)/L}；近圆弧分支下不参与求值。 */
        private final double rate;
        /** true = 用中点曲率圆弧闭式（近圆弧 / 曲率变化率溢出）。 */
        private final boolean circular;
        /** 圆弧分支的曲率（中点曲率），或 0（直线）。 */
        private final double curvature;
        /** {@code sign(a)}，Fresnel 分支的符号。 */
        private final double sign;
        /** {@code sqrt(π/|a|)}。 */
        private final double amplitude;
        /** {@code sqrt(|a|/π)}，局部弧长 → Fresnel 参数的缩放。 */
        private final double argumentScale;
        /** {@code u0 = κ0/a}。 */
        private final double offset;
        /** {@code cos(c)}、{@code sin(c)}，{@code c = κ0²/(2a)}。 */
        private final double cosPhase;
        private final double sinPhase;
        /** {@code t(0) = u0·sqrt(|a|/π)}。 */
        private final double startArgument;

        ClothoidExpression(Vec3 origin, Vec3 tangent, Vec3 left, double length,
                           double startCurvature, double endCurvature) {
            this.origin = origin;
            this.tangent = tangent;
            this.left = left;
            this.length = length;
            this.startCurvature = startCurvature;
            double a = (endCurvature - startCurvature) / length;
            // 近圆弧：|a|L² 太小，Fresnel 配方的相位 c = κ0²/(2a) 会放大舍入误差；
            // a 溢出（length 为次正规数）时同理——此时 |θ| ≤ |Δκ|L/2 < 1e-308，直线/圆弧闭式即精确。
            boolean useCircle = !Double.isFinite(a) || Math.abs(a) * length * length <= NEAR_CIRCULAR;
            this.circular = useCircle;
            if (useCircle) {
                // 中点曲率；拆成两半相加避免 κ0+κ1 溢出。
                this.curvature = 0.5 * startCurvature + 0.5 * endCurvature;
                this.rate = 0.0;
                this.sign = 0.0;
                this.amplitude = 0.0;
                this.argumentScale = 0.0;
                this.offset = 0.0;
                this.cosPhase = 0.0;
                this.sinPhase = 0.0;
                this.startArgument = 0.0;
            } else {
                this.curvature = 0.0;
                this.rate = a;
                this.sign = a > 0.0 ? 1.0 : -1.0;
                this.amplitude = Math.sqrt(Math.PI / Math.abs(a));
                this.argumentScale = Math.sqrt(Math.abs(a) / Math.PI);
                this.offset = startCurvature / a;
                double phase = startCurvature * startCurvature / (2.0 * a);
                this.cosPhase = Math.cos(phase);
                this.sinPhase = Math.sin(phase);
                this.startArgument = offset * argumentScale;
            }
        }

        @Override
        public String type() {
            return "clothoid";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            double s = length * PathDomain.UNIT.clamp(u);
            if (circular) {
                double x;
                double y;
                if (curvature == 0.0) {
                    x = s;
                    y = 0.0;
                } else {
                    double angle = curvature * s;
                    x = Math.sin(angle) / curvature;
                    // 1 - cos θ = 2 sin²(θ/2)：小角度下没有相消误差。
                    double halfSin = Math.sin(0.5 * angle);
                    y = 2.0 * halfSin * halfSin / curvature;
                }
                return new Vec3(origin.x + tangent.x * x + left.x * y,
                        origin.y + tangent.y * x + left.y * y,
                        origin.z + tangent.z * x + left.z * y);
            }
            double argument = (s + offset) * argumentScale;
            double deltaC = Fresnel.c(argument) - Fresnel.c(startArgument);
            double deltaS = Fresnel.s(argument) - Fresnel.s(startArgument);
            double x = amplitude * (cosPhase * deltaC + sign * sinPhase * deltaS);
            double y = amplitude * (sign * cosPhase * deltaS - sinPhase * deltaC);
            return new Vec3(origin.x + tangent.x * x + left.x * y,
                    origin.y + tangent.y * x + left.y * y,
                    origin.z + tangent.z * x + left.z * y);
        }

        @Override
        public Vec3 derivative(double u) {
            double s = length * PathDomain.UNIT.clamp(u);
            double angle = theta(s);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return new Vec3((tangent.x * cos + left.x * sin) * length,
                    (tangent.y * cos + left.y * sin) * length,
                    (tangent.z * cos + left.z * sin) * length);
        }

        /** {@code P'' = L²·κ(s)·(-sinθ·T0 + cosθ·N0)}：切线角对弧长的导数就是曲率。 */
        @Override
        public Vec3 secondDerivative(double u) {
            double s = length * PathDomain.UNIT.clamp(u);
            double angle = theta(s);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double factor = length * length * curvatureAt(s);
            return new Vec3((left.x * cos - tangent.x * sin) * factor,
                    (left.y * cos - tangent.y * sin) * factor,
                    (left.z * cos - tangent.z * sin) * factor);
        }

        /** 弧长参数化：{@code |dP/du| = L}，纯标量、零分配。 */
        @Override
        public double speed(double u) {
            return length;
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_ARC_LENGTH
                    || capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }

        @Override
        public double arcLength(double u0, double u1) {
            return length * (u1 - u0);
        }

        /** 切线角 {@code θ(s) = κ0 s + a s²/2}；近圆弧分支取中点曲率的线性相位。 */
        private double theta(double s) {
            return circular ? curvature * s : startCurvature * s + 0.5 * rate * s * s;
        }

        /** 曲率 {@code κ(s) = κ0 + a s}；近圆弧分支取中点曲率。 */
        private double curvatureAt(double s) {
            return circular ? curvature : startCurvature + rate * s;
        }
    }
}
