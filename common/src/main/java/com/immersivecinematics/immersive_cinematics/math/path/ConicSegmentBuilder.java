package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 精确圆锥段构造器（包内共享）：把单位圆上的角度区间 {@code [θ0, θ1]}（度）按
 * {@code n = ceil(|Δ| / 90)} 等分成 {@code n} 段有理二次 Bézier，再经仿射映射变到世界。
 * {@link ArcPathSpec} / {@link EllipsePathSpec} 共用同一份构造与求值，不各自实现一套。
 *
 * <p>每段圆心角 {@code Δs = Δ / n}（{@code |Δs| <= 90°}），在单位圆空间取
 * {@code P0 = (cos θa, sin θa)}、{@code P2 = (cos θb, sin θb)}、
 * {@code P1 = (cos θm, sin θm) / cos(Δs/2)}、中间权重 {@code w1 = cos(Δs/2)}，
 * 端点权重恒为 1。{@code |Δs| <= 90°} 保证 {@code w1 >= cos 45° > 0}，
 * 因此权重和 {@code D(t)} 在 {@code t ∈ [0,1]} 上恒正（端点处恰为 1），位置不会除零。
 *
 * <p>仿射映射 {@code C + a·cosθ·u' + b·sinθ·v'}：{@code u' / v'} 是法向平面内一组正交单位基
 * （椭圆再按 {@code rotation} 在平面内旋转长短轴）。有理 Bézier 对仿射变换封闭，映射后的曲线
 * 仍精确落在圆 / 椭圆上——不是多项式近似。
 *
 * <p>参数 {@code u ∈ [0,1]} 线性映射到段序列：{@code i = min(n-1, floor(u*n))}、
 * {@code t = u*n - i}；段内 {@code t ∈ [0,1]} 是这段有理二次 Bézier 的局部参数，
 * 所以 {@code dP/du = n · dP/dt}、{@code d²P/du² = n² · d²P/dt²}。
 *
 * <p>构造期算好每段的局部控制点（相对圆心）与中间权重，求值只读数组：{@code position} /
 * {@code derivative} / {@code secondDerivative} 只 new 一个 {@link Vec3}，
 * {@code speed} 不分配任何对象。
 */
final class ConicSegmentBuilder {

    /** 单段圆心角上限（度）：超过就必须分段，否则中间权重 {@code cos(Δs/2)} 失去正性。 */
    private static final double MAX_SEGMENT_DEGREES = 90.0;

    /** 角度跨度上限（度）。 */
    static final double MAX_SWEEP_DEGREES = 360.0;

    /** 法向平面坐标系退化阈值：归一化后叉积模长（= 夹角正弦）低于它就拒绝。 */
    static final double PARALLEL_EPSILON = 1e-9;

    /** 缺省法向：世界竖直。 */
    static final Vec3 DEFAULT_NORMAL = new Vec3(0.0, 1.0, 0.0);

    /** 缺省参考轴：世界 +X（角度 0 的方向）。 */
    static final Vec3 DEFAULT_REFERENCE_AXIS = new Vec3(1.0, 0.0, 0.0);

    private final double centerX;
    private final double centerY;
    private final double centerZ;

    /** 段数 {@code n = ceil(|Δ| / 90)}，恒 ≥ 1。 */
    private final int segments;

    /** 每段三个局部控制点（相对圆心），布局 {@code [3i] / [3i+1] / [3i+2]}。 */
    private final double[] controlX;
    private final double[] controlY;
    private final double[] controlZ;

    /** 每段中间控制点的权重 {@code cos(Δs/2)}；端点权重恒为 1，不单独存。 */
    private final double[] middleWeights;

    /** 解析弧长系数（世界单位 / u）：{@code R·|Δθ|}(弧度)；椭圆为 {@link Double#NaN}。 */
    private final double arcLengthPerUnit;

    /**
     * 圆弧构造：{@code a = b = radius}，长短轴不旋转。
     *
     * @param center        圆心（世界坐标，有限）
     * @param radius        半径（世界单位，有限正数）
     * @param startDegrees  起始角（度，有限）
     * @param endDegrees    结束角（度，有限；跨度非零且 {@code |Δ| <= 360}）
     * @param normal        圆弧所在平面的法向（有限非零，不得与 {@code referenceAxis} 平行）
     * @param referenceAxis 起始角 0 的参考轴（有限非零）
     */
    static ConicSegmentBuilder circle(Vec3 center, double radius, double startDegrees, double endDegrees,
                                      Vec3 normal, Vec3 referenceAxis) {
        return new ConicSegmentBuilder(center, radius, radius, 0.0, normal, referenceAxis,
                startDegrees, endDegrees, true);
    }

    /**
     * 椭圆构造：{@code a = semiMajor}、{@code b = semiMinor}，长短轴在 {@code u/v} 平面内旋转
     * {@code rotationDegrees}。
     *
     * @param center          圆心（世界坐标，有限）
     * @param semiMajor       长半轴（世界单位，有限正数）
     * @param semiMinor       短半轴（世界单位，有限正数）
     * @param rotationDegrees 长短轴在法向平面内的旋转（度，有限）
     * @param startDegrees    起始参数角（度，有限）
     * @param endDegrees      结束参数角（度，有限；跨度非零且 {@code |Δ| <= 360}）
     * @param normal          椭圆所在平面的法向（有限非零，不得与 {@code referenceAxis} 平行）
     * @param referenceAxis   参数角 0 的参考轴（有限非零）
     */
    static ConicSegmentBuilder ellipse(Vec3 center, double semiMajor, double semiMinor,
                                       double rotationDegrees, double startDegrees, double endDegrees,
                                       Vec3 normal, Vec3 referenceAxis) {
        return new ConicSegmentBuilder(center, semiMajor, semiMinor, rotationDegrees, normal, referenceAxis,
                startDegrees, endDegrees, false);
    }

    private ConicSegmentBuilder(Vec3 center, double a, double b, double rotationDegrees,
                                Vec3 normal, Vec3 referenceAxis,
                                double startDegrees, double endDegrees, boolean circularArc) {
        if (center == null || !PathMath.isFinite(center)) {
            throw new IllegalArgumentException("圆心必须是有限向量: center=" + center);
        }
        if (!Double.isFinite(a) || a <= 0.0 || !Double.isFinite(b) || b <= 0.0) {
            throw new IllegalArgumentException("半径 / 半轴必须是有限正数: a=" + a + ", b=" + b);
        }
        if (!Double.isFinite(rotationDegrees)) {
            throw new IllegalArgumentException("旋转角必须是有限数字（度）: rotation=" + rotationDegrees);
        }
        if (!Double.isFinite(startDegrees) || !Double.isFinite(endDegrees)) {
            throw new IllegalArgumentException("起止角必须是有限数字（度）: start_angle=" + startDegrees
                    + ", end_angle=" + endDegrees);
        }
        double sweep = endDegrees - startDegrees;
        if (sweep == 0.0) {
            throw new IllegalArgumentException("起止角不能相同（跨度 0，无法确定弧段）: start_angle=" + startDegrees
                    + ", end_angle=" + endDegrees);
        }
        if (Math.abs(sweep) > MAX_SWEEP_DEGREES) {
            throw new IllegalArgumentException("角度跨度不能超过 360°: start_angle=" + startDegrees
                    + ", end_angle=" + endDegrees + ", |Δ|=" + Math.abs(sweep));
        }
        Vec3 unitNormal = unit(normal, "normal");
        Vec3 unitReference = unit(referenceAxis, "reference_axis");
        double crossLength = PathMath.length(PathMath.cross(unitNormal, unitReference));
        if (crossLength < PARALLEL_EPSILON) {
            throw new IllegalArgumentException("normal 与 reference_axis 不能平行（归一化叉积模长 " + crossLength
                    + " < " + PARALLEL_EPSILON + "，坐标系退化）: normal=" + normal
                    + ", reference_axis=" + referenceAxis);
        }

        // 法向平面基：u = 参考轴在平面内的投影，v = n × u；两者正交单位。
        Vec3 projected = unitReference.subtract(unitNormal.scale(unitNormal.dot(unitReference)));
        double projectedLength = PathMath.length(projected);
        Vec3 uAxis = projectedLength < PARALLEL_EPSILON
                ? anyPerpendicular(unitNormal)   // 防御：上面的平行检查已挡住这条路径
                : projected.scale(1.0 / projectedLength);
        Vec3 vAxis = PathMath.cross(unitNormal, uAxis);
        vAxis = vAxis.scale(1.0 / PathMath.length(vAxis));

        // 椭圆的长短轴在平面内旋转：u' = u cos + v sin，v' = -u sin + v cos。
        double rotation = Math.toRadians(rotationDegrees);
        double cosRotation = Math.cos(rotation);
        double sinRotation = Math.sin(rotation);
        Vec3 majorAxis = uAxis.scale(cosRotation).add(vAxis.scale(sinRotation));
        Vec3 minorAxis = vAxis.scale(cosRotation).subtract(uAxis.scale(sinRotation));

        this.centerX = center.x;
        this.centerY = center.y;
        this.centerZ = center.z;
        this.segments = (int) Math.ceil(Math.abs(sweep) / MAX_SEGMENT_DEGREES);
        this.arcLengthPerUnit = circularArc ? a * Math.abs(Math.toRadians(sweep)) : Double.NaN;

        int count = segments;
        this.controlX = new double[count * 3];
        this.controlY = new double[count * 3];
        this.controlZ = new double[count * 3];
        this.middleWeights = new double[count];

        double stepRadians = Math.toRadians(sweep) / count;
        double halfStep = 0.5 * stepRadians;
        double weight = Math.cos(halfStep);
        double startRadians = Math.toRadians(startDegrees);
        for (int i = 0; i < count; i++) {
            // 用全局角公式算三个角（而不是 angle0 + Δs 递推）：相邻段共享的那个角逐位相同，
            // 接缝处的 P2 / P0 因此完全一致（G0 无舍入缝）。
            double angle0 = startRadians + stepRadians * i;
            double angle1 = startRadians + stepRadians * (i + 1);
            double angleMid = startRadians + stepRadians * (i + 0.5);
            int base = i * 3;
            // 单位圆上的端点直接落点；中间控制点 = 中点 / cos(Δs/2)，再经仿射映射。
            controlX[base] = a * Math.cos(angle0) * majorAxis.x + b * Math.sin(angle0) * minorAxis.x;
            controlY[base] = a * Math.cos(angle0) * majorAxis.y + b * Math.sin(angle0) * minorAxis.y;
            controlZ[base] = a * Math.cos(angle0) * majorAxis.z + b * Math.sin(angle0) * minorAxis.z;
            controlX[base + 1] = (a * Math.cos(angleMid) * majorAxis.x + b * Math.sin(angleMid) * minorAxis.x) / weight;
            controlY[base + 1] = (a * Math.cos(angleMid) * majorAxis.y + b * Math.sin(angleMid) * minorAxis.y) / weight;
            controlZ[base + 1] = (a * Math.cos(angleMid) * majorAxis.z + b * Math.sin(angleMid) * minorAxis.z) / weight;
            controlX[base + 2] = a * Math.cos(angle1) * majorAxis.x + b * Math.sin(angle1) * minorAxis.x;
            controlY[base + 2] = a * Math.cos(angle1) * majorAxis.y + b * Math.sin(angle1) * minorAxis.y;
            controlZ[base + 2] = a * Math.cos(angle1) * majorAxis.z + b * Math.sin(angle1) * minorAxis.z;
            middleWeights[i] = weight;
        }
    }

    /** u 处的位置（世界坐标）。 */
    Vec3 position(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double w1 = middleWeights[index];
        double denominator = b0 + w1 * b1 + b2;
        int base = index * 3;
        return new Vec3(
                centerX + (b0 * controlX[base] + w1 * b1 * controlX[base + 1] + b2 * controlX[base + 2]) / denominator,
                centerY + (b0 * controlY[base] + w1 * b1 * controlY[base + 1] + b2 * controlY[base + 2]) / denominator,
                centerZ + (b0 * controlZ[base] + w1 * b1 * controlZ[base + 1] + b2 * controlZ[base + 2]) / denominator);
    }

    /** u 处的一阶导 {@code dP/du}（世界单位 / u）。 */
    Vec3 derivative(double u) {
        return new Vec3(derivativeX(u), derivativeY(u), derivativeZ(u));
    }

    /** u 处的一阶导模长；与 {@link #derivative(double)} 共用同一组标量辅助方法，不分配对象。 */
    double speed(double u) {
        double dx = derivativeX(u);
        double dy = derivativeY(u);
        double dz = derivativeZ(u);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** u 处的二阶导 {@code d²P/du²}（世界单位 / u²），商法则解析求值。 */
    Vec3 secondDerivative(double u) {
        return new Vec3(secondDerivativeX(u), secondDerivativeY(u), secondDerivativeZ(u));
    }

    /**
     * 解析弧长 {@code R·|Δθ|·(u1 - u0)}（弧度），仅圆弧可用；椭圆没有初等闭式解，
     * 调用方按能力声明选择数值积分。
     */
    double arcLength(double u0, double u1) {
        if (Double.isNaN(arcLengthPerUnit)) {
            throw new IllegalStateException("椭圆弧长没有解析闭式解");
        }
        return arcLengthPerUnit * (u1 - u0);
    }

    /** 段索引 {@code min(n-1, floor(u*n))}：u 越界只夹取索引，段内参数照常外推。 */
    private int segmentIndex(double u) {
        double scaled = u * segments;
        int index = (int) Math.floor(scaled);
        if (index < 0) {
            return 0;
        }
        return Math.min(index, segments - 1);
    }

    /** 段内局部参数 {@code t = u*n - i}。 */
    private double localT(double u, int index) {
        return u * segments - index;
    }

    /** 一阶导 x 分量：商法则 {@code (N'D - ND')/D²}，再乘 {@code n = dt/du 的倒数}。 */
    private double derivativeX(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double w1 = middleWeights[index];
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double d0 = -2.0 * s;
        double d1 = 2.0 - 4.0 * t;
        double d2 = 2.0 * t;
        double denominator = b0 + w1 * b1 + b2;
        double denominatorFirst = d0 + w1 * d1 + d2;
        int base = index * 3;
        double numerator = b0 * controlX[base] + w1 * b1 * controlX[base + 1] + b2 * controlX[base + 2];
        double numeratorFirst = d0 * controlX[base] + w1 * d1 * controlX[base + 1] + d2 * controlX[base + 2];
        return (numeratorFirst * denominator - numerator * denominatorFirst) / (denominator * denominator)
                * segments;
    }

    private double derivativeY(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double w1 = middleWeights[index];
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double d0 = -2.0 * s;
        double d1 = 2.0 - 4.0 * t;
        double d2 = 2.0 * t;
        double denominator = b0 + w1 * b1 + b2;
        double denominatorFirst = d0 + w1 * d1 + d2;
        int base = index * 3;
        double numerator = b0 * controlY[base] + w1 * b1 * controlY[base + 1] + b2 * controlY[base + 2];
        double numeratorFirst = d0 * controlY[base] + w1 * d1 * controlY[base + 1] + d2 * controlY[base + 2];
        return (numeratorFirst * denominator - numerator * denominatorFirst) / (denominator * denominator)
                * segments;
    }

    private double derivativeZ(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double w1 = middleWeights[index];
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double d0 = -2.0 * s;
        double d1 = 2.0 - 4.0 * t;
        double d2 = 2.0 * t;
        double denominator = b0 + w1 * b1 + b2;
        double denominatorFirst = d0 + w1 * d1 + d2;
        int base = index * 3;
        double numerator = b0 * controlZ[base] + w1 * b1 * controlZ[base + 1] + b2 * controlZ[base + 2];
        double numeratorFirst = d0 * controlZ[base] + w1 * d1 * controlZ[base + 1] + d2 * controlZ[base + 2];
        return (numeratorFirst * denominator - numerator * denominatorFirst) / (denominator * denominator)
                * segments;
    }

    /** 二阶导 x 分量：{@code (N''D - ND'')/D² - 2(N'D - ND')D'/D³}，再乘 {@code n²}。 */
    private double secondDerivativeX(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double w1 = middleWeights[index];
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double d0 = -2.0 * s;
        double d1 = 2.0 - 4.0 * t;
        double d2 = 2.0 * t;
        double e0 = 2.0;
        double e1 = -4.0;
        double e2 = 2.0;
        double denominator = b0 + w1 * b1 + b2;
        double denominatorFirst = d0 + w1 * d1 + d2;
        double denominatorSecond = e0 + w1 * e1 + e2;
        int base = index * 3;
        double numerator = b0 * controlX[base] + w1 * b1 * controlX[base + 1] + b2 * controlX[base + 2];
        double numeratorFirst = d0 * controlX[base] + w1 * d1 * controlX[base + 1] + d2 * controlX[base + 2];
        double numeratorSecond = e0 * controlX[base] + w1 * e1 * controlX[base + 1] + e2 * controlX[base + 2];
        double inverse = 1.0 / denominator;
        double first = (numeratorFirst * denominator - numerator * denominatorFirst) * inverse * inverse;
        double second = (numeratorSecond * denominator - numerator * denominatorSecond) * inverse * inverse
                - 2.0 * first * denominatorFirst * inverse;
        return second * segments * segments;
    }

    private double secondDerivativeY(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double w1 = middleWeights[index];
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double d0 = -2.0 * s;
        double d1 = 2.0 - 4.0 * t;
        double d2 = 2.0 * t;
        double e0 = 2.0;
        double e1 = -4.0;
        double e2 = 2.0;
        double denominator = b0 + w1 * b1 + b2;
        double denominatorFirst = d0 + w1 * d1 + d2;
        double denominatorSecond = e0 + w1 * e1 + e2;
        int base = index * 3;
        double numerator = b0 * controlY[base] + w1 * b1 * controlY[base + 1] + b2 * controlY[base + 2];
        double numeratorFirst = d0 * controlY[base] + w1 * d1 * controlY[base + 1] + d2 * controlY[base + 2];
        double numeratorSecond = e0 * controlY[base] + w1 * e1 * controlY[base + 1] + e2 * controlY[base + 2];
        double inverse = 1.0 / denominator;
        double first = (numeratorFirst * denominator - numerator * denominatorFirst) * inverse * inverse;
        double second = (numeratorSecond * denominator - numerator * denominatorSecond) * inverse * inverse
                - 2.0 * first * denominatorFirst * inverse;
        return second * segments * segments;
    }

    private double secondDerivativeZ(double u) {
        int index = segmentIndex(u);
        double t = localT(u, index);
        double s = 1.0 - t;
        double w1 = middleWeights[index];
        double b0 = s * s;
        double b1 = 2.0 * t * s;
        double b2 = t * t;
        double d0 = -2.0 * s;
        double d1 = 2.0 - 4.0 * t;
        double d2 = 2.0 * t;
        double e0 = 2.0;
        double e1 = -4.0;
        double e2 = 2.0;
        double denominator = b0 + w1 * b1 + b2;
        double denominatorFirst = d0 + w1 * d1 + d2;
        double denominatorSecond = e0 + w1 * e1 + e2;
        int base = index * 3;
        double numerator = b0 * controlZ[base] + w1 * b1 * controlZ[base + 1] + b2 * controlZ[base + 2];
        double numeratorFirst = d0 * controlZ[base] + w1 * d1 * controlZ[base + 1] + d2 * controlZ[base + 2];
        double numeratorSecond = e0 * controlZ[base] + w1 * e1 * controlZ[base + 1] + e2 * controlZ[base + 2];
        double inverse = 1.0 / denominator;
        double first = (numeratorFirst * denominator - numerator * denominatorFirst) * inverse * inverse;
        double second = (numeratorSecond * denominator - numerator * denominatorSecond) * inverse * inverse
                - 2.0 * first * denominatorFirst * inverse;
        return second * segments * segments;
    }

    /** 归一化非零有限向量；非法值抛 {@link IllegalArgumentException}。 */
    private static Vec3 unit(Vec3 value, String name) {
        if (value == null || !PathMath.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限向量: " + name + "=" + value);
        }
        double length = PathMath.length(value);
        if (!(length > 0.0)) {
            throw new IllegalArgumentException(name + " 不能是零向量: " + name + "=" + value);
        }
        return value.scale(1.0 / length);
    }

    /** 与 {@code n} 垂直的任一单位向量（防御分支，正常由平行检查拦下）。 */
    private static Vec3 anyPerpendicular(Vec3 n) {
        Vec3 axis = Math.abs(n.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 perpendicular = axis.subtract(n.scale(n.dot(axis)));
        return perpendicular.scale(1.0 / PathMath.length(perpendicular));
    }
}
