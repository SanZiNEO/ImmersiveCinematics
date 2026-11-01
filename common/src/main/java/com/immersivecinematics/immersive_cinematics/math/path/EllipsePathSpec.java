package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 精确椭圆弧路径：圆心 + 长短半轴 + 平面内旋转 + 起止参数角（度）+ 法向平面。
 * 内部对单位圆做仿射映射 {@code C + a·cosθ·u' + b·sinθ·v'}，再由
 * {@link ConicSegmentBuilder} 展开成 {@code n = ceil(|Δ| / 90)} 段有理二次 Bézier；
 * 有理 Bézier 对仿射变换封闭，所以曲线精确落在椭圆上。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#FREE}：几何由参数决定，调用点端点不参与
 * ——只有相对形态的 {@code center} 在 {@code bind} 时按段起点解析。
 *
 * <p>坐标系与旋转：{@code u = normalize(reference_axis - normal·(normal·reference_axis))}、
 * {@code v = normalize(normal × u)}；{@code u' = u·cos(rot) + v·sin(rot)}、
 * {@code v' = -u·sin(rot) + v·cos(rot)}，{@code point(θ) = C + a·cosθ·u' + b·sinθ·v'}。
 * 起止角是单位圆参数角（偏近点角），不是几何极角；旋转后两者不再相同。
 *
 * <p>参数 {@code u ∈ [0,1]} 线性映射到段序列；{@code position / derivative / secondDerivative /
 * speed} 全部解析，{@code speed} 零分配。椭圆弧长没有初等闭式解，因此只声明
 * {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}，弧长走数值积分 / 弧长表。
 *
 * <p>参数非法（半轴非正、跨度为 0 或超 360°、法向与参考轴平行等）在构造器内抛
 * {@link IllegalArgumentException}，不静默回落直线。
 */
public final class EllipsePathSpec implements PathSpec {

    /** 缺省法向：世界竖直。 */
    public static final Vec3 DEFAULT_NORMAL = ConicSegmentBuilder.DEFAULT_NORMAL;

    /** 缺省参考轴：世界 +X（参数角 0 的方向）。 */
    public static final Vec3 DEFAULT_REFERENCE_AXIS = ConicSegmentBuilder.DEFAULT_REFERENCE_AXIS;

    private final PathPoint center;
    private final double semiMajor;
    private final double semiMinor;
    private final double rotation;
    private final double startAngle;
    private final double endAngle;
    private final Vec3 normal;
    private final Vec3 referenceAxis;
    /** 绝对圆心的预构造几何；相对圆心时为 null（{@link #bind} 按段起点重建）。 */
    private final ConicSegmentBuilder fixedGeometry;

    /**
     * 椭圆弧定义；{@code normal} / {@code referenceAxis} 传 null 时取缺省值。
     *
     * @param center        圆心；相对形态（{@code {dx,dy,dz}}）以段起点（{@code bind} 的 {@code from}）为基准
     * @param semiMajor     长半轴（世界单位，有限正数）
     * @param semiMinor     短半轴（世界单位，有限正数）
     * @param rotation      长短轴在法向平面内的旋转（度，有限）
     * @param startAngle    起始参数角（度，有限）
     * @param endAngle      结束参数角（度，有限；{@code Δ != 0} 且 {@code |Δ| <= 360}）
     * @param normal        椭圆所在平面的法向（有限非零，不得与 {@code referenceAxis} 平行）
     * @param referenceAxis 参数角 0 的参考轴（有限非零）
     */
    public EllipsePathSpec(PathPoint center, double semiMajor, double semiMinor, double rotation,
                           double startAngle, double endAngle, Vec3 normal, Vec3 referenceAxis) {
        if (center == null) {
            throw new IllegalArgumentException("圆心不能为 null: center=null");
        }
        if (!center.isFinite()) {
            throw new IllegalArgumentException("圆心必须是有限坐标: center=" + center);
        }
        this.center = center;
        this.semiMajor = semiMajor;
        this.semiMinor = semiMinor;
        this.rotation = rotation;
        this.startAngle = startAngle;
        this.endAngle = endAngle;
        this.normal = normal == null ? DEFAULT_NORMAL : normal;
        this.referenceAxis = referenceAxis == null ? DEFAULT_REFERENCE_AXIS : referenceAxis;
        // 构造期用原始分量建一次：参数校验（半轴 / 角度 / 基向量）在这里发生，非法值直接抛。
        ConicSegmentBuilder geometry = ConicSegmentBuilder.ellipse(center.toVec3(), semiMajor, semiMinor, rotation,
                startAngle, endAngle, this.normal, this.referenceAxis);
        this.fixedGeometry = center.relative() ? null : geometry;
    }

    @Override
    public String type() {
        return "ellipse";
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
            return new EllipseExpression(fixedGeometry);
        }
        return new EllipseExpression(ConicSegmentBuilder.ellipse(center.resolve(from), semiMajor, semiMinor,
                rotation, startAngle, endAngle, normal, referenceAxis));
    }

    @Override
    public boolean supports(PathCapability capability) {
        return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
    }

    /** 圆心（相对形态未解析，需 {@link PathPoint#resolve(Vec3)}）。 */
    public PathPoint center() {
        return center;
    }

    /** 长半轴（世界单位）。 */
    public double semiMajor() {
        return semiMajor;
    }

    /** 短半轴（世界单位）。 */
    public double semiMinor() {
        return semiMinor;
    }

    /** 长短轴在法向平面内的旋转（度）。 */
    public double rotation() {
        return rotation;
    }

    /** 起始参数角（度）。 */
    public double startAngle() {
        return startAngle;
    }

    /** 结束参数角（度）。 */
    public double endAngle() {
        return endAngle;
    }

    /** 椭圆所在平面的法向（已按构造值保存，未归一化）。 */
    public Vec3 normal() {
        return normal;
    }

    /** 参数角 0 的参考轴（已按构造值保存，未归一化）。 */
    public Vec3 referenceAxis() {
        return referenceAxis;
    }

    /** 段数 {@code ceil(|Δ| / 90)}（与圆心是否相对无关）。 */
    public int segments() {
        return (int) Math.ceil(Math.abs(endAngle - startAngle) / 90.0);
    }

    private static final class EllipseExpression implements PathExpression {

        private final ConicSegmentBuilder geometry;

        EllipseExpression(ConicSegmentBuilder geometry) {
            this.geometry = geometry;
        }

        @Override
        public String type() {
            return "ellipse";
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
            return capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE;
        }
    }
}
