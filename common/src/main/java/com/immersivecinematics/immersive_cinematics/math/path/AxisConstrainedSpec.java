package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 轴约束包装：{@code bind(from, to)} 先委派原定义，再把未选轴分量替换为端点间线性。
 * 已选轴保留原曲线的位置 / 导数；未选轴位置 = {@code from + (to-from) * normalize(u)}，
 * 未选轴导数 = {@code (to-from) / span}。{@link #type()} 为原类型加 {@code "|axis"} 后缀。
 *
 * <p><b>几何口径</b>：算子作用在求值结果上，不是作用在控制数据上——未选轴严格是端点间线性函数，
 * 已选轴逐位保留原曲线分量。对 Bézier / 二次 Bézier / B-spline，这与「把未选轴控制点摆到端点线性
 * 对应位置」数学等价（线性精度）；对 NURBS / 圆锥曲线，控制点约束会破坏权重与精确性，
 * 因此这里统一取几何口径，该轴同样严格线性。
 *
 * <p><b>锚点</b>：{@code anchor() == FREE} 的原定义（圆弧 / 椭圆 / 回旋线等）不消费调用点端点，
 * 但未选轴仍以 {@code bind} 收到的 {@code from} / {@code to} 作线性基准——它是一段路径的
 * 起止位置，不是曲线参数。{@code anchor() == START} 时 {@code to} 只用于未选轴的线性目标，
 * 不参与原曲线定义。
 *
 * <p><b>能力</b>：约束改变了几何，弧长不再等于原曲线的弧长，因此只有
 * {@link AxisConstraint#constrainsAny()} 为 {@code false}（三轴全保留，即
 * {@link AxisConstraint#ALL}）时才把 {@link PathCapability#ANALYTIC_ARC_LENGTH} 透传下去并
 * 实现 {@link AnalyticArcLength}；有任一轴被约束时表达式不实现该接口，弧长由算子数值积分。
 */
public final class AxisConstrainedSpec implements PathSpec {

    private final PathSpec delegate;
    private final AxisConstraint constraint;

    /** 包装任意定义；{@code delegate} / {@code constraint} 不可为 null。 */
    public AxisConstrainedSpec(PathSpec delegate, AxisConstraint constraint) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        if (constraint == null) {
            throw new IllegalArgumentException("constraint 不能为 null");
        }
        this.delegate = delegate;
        this.constraint = constraint;
    }

    /** 被包装的原定义。 */
    public PathSpec delegate() {
        return delegate;
    }

    /** 轴选择。 */
    public AxisConstraint constraint() {
        return constraint;
    }

    @Override
    public String type() {
        return delegate.type() + "|axis";
    }

    @Override
    public PathDomain domain() {
        return delegate.domain();
    }

    @Override
    public PathAnchor anchor() {
        return delegate.anchor();
    }

    /**
     * 用调用点端点绑定：原定义照常绑定一次，未选轴改用 {@code from → to} 的线性基准。
     * 对 {@code anchor() == FREE} 的原定义，{@code from} / {@code to} 不参与原曲线，只用于未选轴的
     * 线性基准与 {@code normalize(u)} 的时间轴；对 {@code ENDPOINTS} / {@code START} 定义两者仍参与原曲线。
     *
     * <p>内层实现 {@link AnalyticArcLength} 且三轴全保留时，返回的表达式也实现该接口并直接委派；
     * 有轴被约束时弧长已变，返回的表达式不实现该接口（弧长必须重算）。
     */
    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("端点不能为 null: from=" + from + ", to=" + to);
        }
        PathExpression inner = delegate.bind(from, to);
        if (!constraint.constrainsAny() && inner instanceof AnalyticArcLength) {
            return new AnalyticAxisConstrainedExpression(inner, from, to, constraint);
        }
        return new AxisConstrainedExpression(inner, from, to, constraint);
    }

    /**
     * 能力透传，但 {@link PathCapability#ANALYTIC_ARC_LENGTH} 例外：任一轴被约束后几何改变、
     * 弧长不再等于原曲线，故此时不声明。
     */
    @Override
    public boolean supports(PathCapability capability) {
        if (capability == PathCapability.ANALYTIC_ARC_LENGTH) {
            return !constraint.constrainsAny() && delegate.supports(capability);
        }
        return delegate.supports(capability);
    }

    /**
     * 轴约束求值器：位置 / 导数 / speed 逐分量替换，未选轴取端点线性、已选轴取内层分量。
     * 本身不实现 {@link AnalyticArcLength}——有轴被约束时弧长已变，委派原解析式会算错。
     */
    private static class AxisConstrainedExpression implements PathExpression {

        private final PathExpression inner;
        private final Vec3 from;
        private final Vec3 delta;
        private final AxisConstraint constraint;
        private final double invSpan;

        AxisConstrainedExpression(PathExpression inner, Vec3 from, Vec3 to,
                                  AxisConstraint constraint) {
            this.inner = inner;
            this.from = from;
            this.delta = to.subtract(from);
            this.constraint = constraint;
            this.invSpan = 1.0 / inner.domain().span();
        }

        @Override
        public String type() {
            return inner.type() + "|axis";
        }

        @Override
        public PathDomain domain() {
            return inner.domain();
        }

        @Override
        public Vec3 position(double u) {
            Vec3 p = inner.position(u);
            double t = inner.domain().normalize(u);
            double x = constraint.x() ? p.x : from.x + delta.x * t;
            double y = constraint.y() ? p.y : from.y + delta.y * t;
            double z = constraint.z() ? p.z : from.z + delta.z * t;
            return new Vec3(x, y, z);
        }

        @Override
        public Vec3 derivative(double u) {
            Vec3 d = inner.derivative(u);
            double x = constraint.x() ? d.x : delta.x * invSpan;
            double y = constraint.y() ? d.y : delta.y * invSpan;
            double z = constraint.z() ? d.z : delta.z * invSpan;
            return new Vec3(x, y, z);
        }

        /**
         * 未选轴取端点线性导数 {@code (to-from)/span}，已选轴取内层导数分量。
         * 内层导数只能按向量取出，包装层每次查询分配一个向量。
         */
        @Override
        public double speed(double u) {
            Vec3 d = inner.derivative(u);
            double x = constraint.x() ? d.x : delta.x * invSpan;
            double y = constraint.y() ? d.y : delta.y * invSpan;
            double z = constraint.z() ? d.z : delta.z * invSpan;
            return Math.sqrt(x * x + y * y + z * z);
        }

        /** 透传内层能力；弧长例外——有轴被约束时几何已变，见类注释。 */
        @Override
        public boolean supports(PathCapability capability) {
            if (capability == PathCapability.ANALYTIC_ARC_LENGTH) {
                return false;
            }
            return inner.supports(capability);
        }

        /** 被包装的内层表达式；供三轴全保留的解析弧长变体委派。 */
        final PathExpression innerExpression() {
            return inner;
        }
    }

    /**
     * 三轴全保留（{@link AxisConstraint#ALL}）时的求值器：几何与原曲线逐位相同，
     * 因此可以把 {@link AnalyticArcLength} 直接委派给内层。
     */
    private static final class AnalyticAxisConstrainedExpression extends AxisConstrainedExpression
            implements AnalyticArcLength {

        AnalyticAxisConstrainedExpression(PathExpression inner, Vec3 from, Vec3 to, AxisConstraint constraint) {
            super(inner, from, to, constraint);
        }

        @Override
        public double arcLength(double u0, double u1) {
            return ((AnalyticArcLength) innerExpression()).arcLength(u0, u1);
        }

        @Override
        public boolean supports(PathCapability capability) {
            if (capability == PathCapability.ANALYTIC_ARC_LENGTH) {
                return true;
            }
            return super.supports(capability);
        }
    }
}
