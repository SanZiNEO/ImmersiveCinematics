package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import net.minecraft.world.phys.Vec3;

/**
 * 精确圆弧路径：圆心 + 半径 + 起止角（度）+ 法向平面，内部由 {@link ConicSegmentBuilder} 展开成
 * {@code n = ceil(|Δ| / 90)} 段有理二次 Bézier，因此曲线精确落在圆上（不是多项式近似）。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#FREE}：几何完全由参数决定，
 * 调用点端点不参与定义——只有相对形态的 {@code center} 在 {@code bind} 时按段起点解析。
 *
 * <p>坐标系：{@code u = normalize(reference_axis - normal·(normal·reference_axis))}、
 * {@code v = normalize(normal × u)}、{@code point(θ) = C + R·(cosθ·u + sinθ·v)}。
 * {@code u} 是起始角 0 的方向，{@code v} 决定正角方向；两者与 {@code normal} 构成右手系。
 *
 * <p>参数 {@code u ∈ [0,1]} 线性映射到段序列；{@code position / derivative / secondDerivative /
 * speed} 全部解析，{@code speed} 零分配。声明
 * {@link PathCapability#ANALYTIC_ARC_LENGTH}（{@code arcLength = R·|Δθ|·(u1-u0)}，弧度）与
 * {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}。
 *
 * <p>参数非法（半径非正、跨度为 0 或超 360°、法向与参考轴平行等）在构造器内抛
 * {@link IllegalArgumentException}，不静默回落直线。
 */
public final class ArcPathSpec implements PathSpec {

    /** 缺省法向：世界竖直。 */
    public static final Vec3 DEFAULT_NORMAL = ConicSegmentBuilder.DEFAULT_NORMAL;

    /** 缺省参考轴：世界 +X（起始角 0 的方向）。 */
    public static final Vec3 DEFAULT_REFERENCE_AXIS = ConicSegmentBuilder.DEFAULT_REFERENCE_AXIS;

    private final PathPoint center;
    private final double radius;
    private final double startAngle;
    private final double endAngle;
    private final Vec3 normal;
    private final Vec3 referenceAxis;
    /** 绝对圆心的预构造几何；相对圆心时为 null（{@link #bind} 按段起点重建）。 */
    private final ConicSegmentBuilder fixedGeometry;

    /**
     * 圆弧定义；{@code normal} / {@code referenceAxis} 传 null 时取缺省值。
     *
     * @param center        圆心；相对形态（{@code {dx,dy,dz}}）以段起点（{@code bind} 的 {@code from}）为基准
     * @param radius        半径（世界单位，有限正数）
     * @param startAngle    起始角（度，有限）
     * @param endAngle      结束角（度，有限；{@code Δ != 0} 且 {@code |Δ| <= 360}）
     * @param normal        圆弧所在平面的法向（有限非零，不得与 {@code referenceAxis} 平行）
     * @param referenceAxis 起始角 0 的参考轴（有限非零）
     */
    public ArcPathSpec(PathPoint center, double radius, double startAngle, double endAngle,
                       Vec3 normal, Vec3 referenceAxis) {
        if (center == null) {
            throw new IllegalArgumentException("圆心不能为 null: center=null");
        }
        if (!center.isFinite()) {
            throw new IllegalArgumentException("圆心必须是有限坐标: center=" + center);
        }
        this.center = center;
        this.radius = radius;
        this.startAngle = startAngle;
        this.endAngle = endAngle;
        this.normal = normal == null ? DEFAULT_NORMAL : normal;
        this.referenceAxis = referenceAxis == null ? DEFAULT_REFERENCE_AXIS : referenceAxis;
        // 构造期用原始分量建一次：参数校验（半径 / 角度 / 基向量）在这里发生，非法值直接抛。
        ConicSegmentBuilder geometry = ConicSegmentBuilder.circle(center.toVec3(), radius, startAngle, endAngle,
                this.normal, this.referenceAxis);
        this.fixedGeometry = center.relative() ? null : geometry;
    }

    @Override
    public String type() {
        return "arc";
    }

    @Override
    public PathDomain domain() {
        return PathDomain.UNIT;
    }

    @Override
    public PathAnchor anchor() {
        return PathAnchor.FREE;
    }

    /**
     * 几何由参数决定（FREE 锚点）：绝对圆心时 {@code from} / {@code to} 都不参与；
     * 相对圆心时圆心按 {@code from} 解析（{@code to} 始终忽略）。
     */
    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (fixedGeometry != null) {
            return new ArcExpression(fixedGeometry);
        }
        return new ArcExpression(ConicSegmentBuilder.circle(center.resolve(from), radius, startAngle, endAngle,
                normal, referenceAxis));
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_ARC_LENGTH
                || capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 圆心（相对形态未解析，需 {@link PathPoint#resolve(Vec3)}）。 */
    public PathPoint center() {
        return center;
    }

    /** 半径（世界单位）。 */
    public double radius() {
        return radius;
    }

    /** 起始角（度）。 */
    public double startAngle() {
        return startAngle;
    }

    /** 结束角（度）。 */
    public double endAngle() {
        return endAngle;
    }

    /** 圆弧所在平面的法向（已按构造值保存，未归一化）。 */
    public Vec3 normal() {
        return normal;
    }

    /** 起始角 0 的参考轴（已按构造值保存，未归一化）。 */
    public Vec3 referenceAxis() {
        return referenceAxis;
    }

    /** 段数 {@code ceil(|Δ| / 90)}（与圆心是否相对无关）。 */
    public int segments() {
        return (int) Math.ceil(Math.abs(endAngle - startAngle) / 90.0);
    }

    private static final class ArcExpression implements PathExpression, AnalyticArcLength {

        private final ConicSegmentBuilder geometry;

        ArcExpression(ConicSegmentBuilder geometry) {
            this.geometry = geometry;
        }

        @Override
        public String type() {
            return "arc";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            return geometry.position(u);
        }

        @Override
        public Vec3 derivative(double u) {
            return geometry.derivative(u);
        }

        @Override
        public double speed(double u) {
            return geometry.speed(u);
        }

        @Override
        public Vec3 secondDerivative(double u) {
            return geometry.secondDerivative(u);
        }

        @Override
        public boolean supports(PathCapability capability) {
            return capability == PathCapability.ANALYTIC_ARC_LENGTH
                    || capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }

        @Override
        public double arcLength(double u0, double u1) {
            return geometry.arcLength(u0, u1);
        }
    }
}
