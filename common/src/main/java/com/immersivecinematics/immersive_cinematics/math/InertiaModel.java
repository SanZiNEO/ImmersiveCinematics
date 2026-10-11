package com.immersivecinematics.immersive_cinematics.math;

/**
 * inertia — 惯性：阻尼基准随输入运动的二阶追踪（{@code e'' = −k·e − 2ζω·e'}，{@code e = x − target}、
 * {@code e' = x' − target'}、{@code ω = √k}）。
 *
 * 与 {@link SpringModel} 的唯一差别 = 阻尼项基准：spring 取绝对速度（跟随移动目标有稳态滞后 {@code 2ζv/ω}）；
 * inertia 取相对速度（与移动目标同速 = 稳态零滞后），动量集中在起步 / 停止处——起步慢、停止后过冲再回稳；
 * 输入静止（{@code target' = 0}）时 = 同参数的 spring。参数名 / 缺省 / 范围与 spring 一致。
 * 位置不连续（cut 过渡 / 循环回卷 / 目标切换）直通：输出随输入跳变，速度（动量）保留并随后衰减。
 *
 * 参考速度 {@code target'} = 输入差分（ANGLE 取最短角差）除以 dt，且不超过上一步估计的幅度——
 * 跳变输入不注入冲量，代价 = 输入速度增大的首帧前馈滞后一帧。
 *
 * 状态：每分量（当前值, 速度, 参考速度, 上一步输入, 上一步估计）；初值 = 首个输入 + {@code initial_velocity}
 * （参考速度 = 0）；输出 = 当前值（ANGLE 不归一）；dt 上限 {@link MathModelInstance#MAX_DT}；
 * 实例状态所有权：实例独占；定义线程安全。
 */
public final class InertiaModel implements MathModel {

    public static final String TYPE = "inertia";
    public static final double DEFAULT_STIFFNESS = 8.0;
    public static final double MIN_STIFFNESS = 1.0e-3;
    public static final double MAX_STIFFNESS = 1.0e3;
    public static final double DEFAULT_DAMPING = 0.75;
    public static final double MIN_DAMPING = 0.0;
    public static final double MAX_DAMPING = 10.0;
    public static final double DEFAULT_INITIAL_VELOCITY = 0.0;
    public static final double MIN_INITIAL_VELOCITY = -1.0e4;
    public static final double MAX_INITIAL_VELOCITY = 1.0e4;

    /** ζ 与 1 的判定带宽：带内走临界公式（欠 / 过阻尼公式在 ζ → 1 处退化）。 */
    private static final double CRITICAL_EPS = 1.0e-6;

    private final ValueDomain domain;
    private final double stiffness;
    private final double damping;
    private final double initialVelocity;

    /** @param stiffness 刚度 k；@param damping 阻尼比 ζ；@param initialVelocity 初速度（越界按 clamp* 钳制） */
    public InertiaModel(ValueDomain domain, double stiffness, double damping, double initialVelocity) {
        this.domain = domain;
        this.stiffness = clampStiffness(stiffness);
        this.damping = clampDamping(damping);
        this.initialVelocity = clampInitialVelocity(initialVelocity);
    }

    /** 刚度：NaN → 缺省；越界 → 钳制到 [1e-3, 1e3]。 */
    public static double clampStiffness(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_STIFFNESS;
        }
        return Math.max(MIN_STIFFNESS, Math.min(v, MAX_STIFFNESS));
    }

    /** 阻尼比：NaN → 缺省；越界 → 钳制到 [0, 10]。 */
    public static double clampDamping(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_DAMPING;
        }
        return Math.max(MIN_DAMPING, Math.min(v, MAX_DAMPING));
    }

    /** 初速度：NaN → 缺省；越界 → 钳制到 ±1e4。 */
    public static double clampInitialVelocity(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_INITIAL_VELOCITY;
        }
        return Math.max(MIN_INITIAL_VELOCITY, Math.min(v, MAX_INITIAL_VELOCITY));
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
        return new Instance(domain.channels(), stiffness, damping, initialVelocity, domain == ValueDomain.ANGLE);
    }

    private static final class Instance extends StatefulInstance {

        private final double stiffness;
        private final double damping;
        private final double initialVelocity;
        private final boolean angle;

        /** 每分量速度（单位/秒），初值 = initial_velocity。 */
        private final double[] velocity;

        /** 每分量参考速度（= 本步输入变化率，经跳变抑制），初值 0。 */
        private final double[] reference;

        /** 每分量上一步输入（差分的基准），初值 = 首个输入。 */
        private final double[] previous;

        /** 每分量上一步的输入变化率估计（跳变抑制的上限基准），初值 0。 */
        private final double[] previousRate;

        Instance(int arity, double stiffness, double damping, double initialVelocity, boolean angle) {
            super(arity);
            this.stiffness = stiffness;
            this.damping = damping;
            this.initialVelocity = initialVelocity;
            this.angle = angle;
            this.velocity = new double[arity];
            this.reference = new double[arity];
            this.previous = new double[arity];
            this.previousRate = new double[arity];
        }

        @Override
        protected void seed(double[] in) {
            System.arraycopy(in, 0, state, 0, state.length);
            java.util.Arrays.fill(velocity, initialVelocity);
            java.util.Arrays.fill(reference, 0.0);
            System.arraycopy(in, 0, previous, 0, state.length);
            java.util.Arrays.fill(previousRate, 0.0);
        }

        @Override
        protected void advance(double dt, double[] target) {
            updateReferences(dt, target);
            if (damping < 1.0 - CRITICAL_EPS) {
                advanceUnderdamped(dt, target);
            } else if (damping > 1.0 + CRITICAL_EPS) {
                advanceOverdamped(dt, target);
            } else {
                advanceCritical(dt, target);
            }
            System.arraycopy(target, 0, previous, 0, previous.length);
        }

        /**
         * 本步参考速度 = 输入变化率（上一帧采样 → 本帧采样），上限 = 上一步估计的幅度（跳变不注入冲量）。
         *
         * {@link #previous} 保持上一帧采样值到本步结束（分支用它算起点误差）。
         */
        private void updateReferences(double dt, double[] target) {
            for (int i = 0; i < state.length; i++) {
                double rate = angle
                        ? AngleUtil.delta(previous[i], target[i]) / dt
                        : (target[i] - previous[i]) / dt;
                double bound = Math.abs(previousRate[i]);
                reference[i] = rate > bound ? bound : (rate < -bound ? -bound : rate);
                previousRate[i] = rate;
            }
        }

        /** 欠阻尼（ζ &lt; 1）：{@code ωd = ω√(1−ζ²)}，相对位移包络 = e^(−ζωt)·(cos ωd t, sin ωd t)。 */
        private void advanceUnderdamped(double dt, double[] target) {
            double omega = Math.sqrt(stiffness);
            double zetaOmega = damping * omega;
            double omegaD = omega * Math.sqrt(1.0 - damping * damping);
            double decay = Math.exp(-zetaOmega * dt);
            double cos = Math.cos(omegaD * dt);
            double sin = Math.sin(omegaD * dt);
            for (int i = 0; i < state.length; i++) {
                double e0 = displacement(i, previous[i]);
                double v0 = velocity[i] - reference[i];
                double e1 = decay * (e0 * cos + (v0 + zetaOmega * e0) / omegaD * sin);
                double v1 = decay * (v0 * cos - (omega * omega * e0 + zetaOmega * v0) / omegaD * sin);
                state[i] = target[i] + e1;
                velocity[i] = reference[i] + v1;
            }
        }

        /** 临界阻尼（ζ = 1）：相对位移 = e^(−ωt)·(e₀ + (v₀ + ω e₀)·t)。 */
        private void advanceCritical(double dt, double[] target) {
            double omega = Math.sqrt(stiffness);
            double decay = Math.exp(-omega * dt);
            for (int i = 0; i < state.length; i++) {
                double e0 = displacement(i, previous[i]);
                double v0 = velocity[i] - reference[i];
                double e1 = decay * (e0 + (v0 + omega * e0) * dt);
                double v1 = decay * (v0 - omega * (v0 + omega * e0) * dt);
                state[i] = target[i] + e1;
                velocity[i] = reference[i] + v1;
            }
        }

        /** 过阻尼（ζ &gt; 1）：两根 {@code r₁ = −ω/(ζ+√(ζ²−1))}、{@code r₂ = −ω(ζ+√(ζ²−1))}（r₁ 用稳定形式）。 */
        private void advanceOverdamped(double dt, double[] target) {
            double omega = Math.sqrt(stiffness);
            double root = Math.sqrt(damping * damping - 1.0);
            double r1 = -omega / (damping + root);
            double r2 = -omega * (damping + root);
            double decay1 = Math.exp(r1 * dt);
            double decay2 = Math.exp(r2 * dt);
            double denominator = r1 - r2;
            for (int i = 0; i < state.length; i++) {
                double e0 = displacement(i, previous[i]);
                double v0 = velocity[i] - reference[i];
                double c1 = (v0 - r2 * e0) / denominator;
                double c2 = e0 - c1;
                state[i] = target[i] + c1 * decay1 + c2 * decay2;
                velocity[i] = reference[i] + c1 * r1 * decay1 + c2 * r2 * decay2;
            }
        }

        /** 位移 e = 当前值 − 采样值（分支用上一帧采样 = 本步起点误差）；ANGLE 域取最短角差（度，跨 ±180° 不绕远路）。 */
        private double displacement(int index, double sample) {
            return angle ? -AngleUtil.delta(state[index], sample) : state[index] - sample;
        }
    }
}
