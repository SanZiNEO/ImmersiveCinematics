package com.immersivecinematics.immersive_cinematics.math;

/**
 * blend — 并联混合：{@code out = a(in)} 与 {@code b(in)} 按 weight 混合。
 *
 * 参数：{@code a} / {@code b} = 子定义（缺省 / 无效由注册表回落 linear = 恒等）；
 * {@code weight} = 权重（缺省 0.5，范围 0 ~ 1；0 = 全 a、1 = 全 b）。
 * 混合方式：ANGLE 域取最短路径（{@code out = a + wrap180(b − a)·w}），其余域逐分量线性。
 *
 * 两个子模型吃同一输入、各自独立状态；缓冲在实例化时一次性分配；step 零分配。
 */
public final class BlendModel implements MathModel {

    public static final String TYPE = "blend";
    public static final double DEFAULT_WEIGHT = 0.5;
    public static final double MIN_WEIGHT = 0.0;
    public static final double MAX_WEIGHT = 1.0;

    private final ValueDomain domain;
    private final MathModel a;
    private final MathModel b;
    private final double weight;

    /** @param a 子模型（非 null；缺省 / 无效由注册表回落 linear）；@param weight 权重，越界 / NaN 按 {@link #clampWeight} 钳制 */
    public BlendModel(ValueDomain domain, MathModel a, MathModel b, double weight) {
        this.domain = domain;
        this.a = a;
        this.b = b;
        this.weight = clampWeight(weight);
    }

    /** 权重：NaN → 缺省；越界 → 钳制到 [0,1]（范围常量单源）。 */
    public static double clampWeight(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_WEIGHT;
        }
        return Math.max(MIN_WEIGHT, Math.min(v, MAX_WEIGHT));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ValueDomain domain() {
        return domain;
    }

    @Override
    public int inputArity() {
        return domain.channels();
    }

    @Override
    public int outputArity() {
        return domain.channels();
    }

    @Override
    public boolean stateful() {
        return a.stateful() || b.stateful();
    }

    @Override
    public MathModelInstance instantiate() {
        return new BlendInstance(a, b, domain, weight);
    }

    private static final class BlendInstance implements MathModelInstance {

        private final MathModelInstance instanceA;
        private final MathModelInstance instanceB;
        private final double[] bufferA;
        private final double[] bufferB;
        private final boolean angle;
        private final double weight;
        private final int arity;

        BlendInstance(MathModel a, MathModel b, ValueDomain domain, double weight) {
            this.arity = domain.channels();
            this.instanceA = a.instantiate();
            this.instanceB = b.instantiate();
            this.bufferA = new double[arity];
            this.bufferB = new double[arity];
            this.angle = domain == ValueDomain.ANGLE;
            this.weight = weight;
        }

        @Override
        public void step(double t, double dt, double[] in, double[] out) {
            instanceA.step(t, dt, in, bufferA);
            instanceB.step(t, dt, in, bufferB);
            for (int i = 0; i < arity; i++) {
                out[i] = angle
                        ? bufferA[i] + AngleUtil.delta(bufferA[i], bufferB[i]) * weight
                        : bufferA[i] + (bufferB[i] - bufferA[i]) * weight;
            }
        }

        @Override
        public void reset() {
            instanceA.reset();
            instanceB.reset();
        }
    }
}
