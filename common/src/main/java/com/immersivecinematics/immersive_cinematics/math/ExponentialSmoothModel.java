package com.immersivecinematics.immersive_cinematics.math;

/**
 * exponential_smooth — 一阶指数趋近（时间常数 τ，秒）。
 *
 * 公式：{@code e = target − x}（ANGLE 域取最短角差），{@code x ← x + e·(1 − exp(−dt/τ))} ——
 * 闭式解，帧率无关（分段相乘 = 整段指数，误差 ≤ 1e-9 相对）。
 *
 * 参数：{@code tau} = 时间常数（秒，缺省 0.15，范围 1e-3 ~ 100；越小越快）。
 * 状态：每分量当前值，初值 = 首个输入（无起始瞬态）；输出 = 当前值（ANGLE 不归一）。
 * 实例状态所有权：实例独占；定义线程安全。
 */
public final class ExponentialSmoothModel implements MathModel {

    /** 模型类型标识（脚本 JSON 的 {@code type} 取值）。 */
    public static final String TYPE = "exponential_smooth";
    /** 缺省时间常数 τ（秒）。 */
    public static final double DEFAULT_TAU = 0.15;
    /** τ 下限（秒）：再小则单帧步长 k→1，状态直接甩到目标。 */
    public static final double MIN_TAU = 1.0e-3;
    /** τ 上限（秒）。 */
    public static final double MAX_TAU = 100.0;

    private final ValueDomain domain;
    private final double tau;

    /** @param tau 时间常数（秒）；越界 / NaN 按 {@link #clampTau} 钳制 */
    public ExponentialSmoothModel(ValueDomain domain, double tau) {
        this.domain = domain;
        this.tau = clampTau(tau);
    }

    /** 时间常数：NaN → 缺省；越界 → 钳制到 [1e-3, 100]（范围常量单源）。 */
    public static double clampTau(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_TAU;
        }
        return Math.max(MIN_TAU, Math.min(v, MAX_TAU));
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
        return true;
    }

    @Override
    public MathModelInstance instantiate() {
        return new Instance(domain.channels(), tau, domain == ValueDomain.ANGLE);
    }

    private static final class Instance extends StatefulInstance {

        private final double tau;
        private final boolean angle;

        Instance(int arity, double tau, boolean angle) {
            super(arity);
            this.tau = tau;
            this.angle = angle;
        }

        @Override
        protected void seed(double[] in) {
            System.arraycopy(in, 0, state, 0, state.length);
        }

        @Override
        protected void advance(double dt, double[] target) {
            double k = 1.0 - Math.exp(-dt / tau);
            for (int i = 0; i < state.length; i++) {
                double error = angle ? AngleUtil.delta(state[i], target[i]) : target[i] - state[i];
                state[i] += error * k;
            }
        }
    }
}
