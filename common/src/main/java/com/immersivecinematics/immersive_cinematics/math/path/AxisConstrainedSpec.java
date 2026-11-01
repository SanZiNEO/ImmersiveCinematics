package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 轴约束包装：{@code bind(from, to)} 先委派原定义，再把未选轴分量替换为端点间线性。
 * 已选轴保留原曲线的位置 / 导数；未选轴位置 = {@code from + (to-from) * normalize(u)}，
 * 未选轴导数 = {@code (to-from) / span}。{@link #type()} 为原类型加 {@code "|axis"} 后缀。
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

    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("端点不能为 null: from=" + from + ", to=" + to);
        }
        return new AxisConstrainedExpression(delegate.bind(from, to), from, to, constraint);
    }

    @Override
    public boolean supports(PathCapability capability) {
        return delegate.supports(capability);
    }

    private static final class AxisConstrainedExpression implements PathExpression {

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

        @Override
        public boolean supports(PathCapability capability) {
            return inner.supports(capability);
        }
    }
}
