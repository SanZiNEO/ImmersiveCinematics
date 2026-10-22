package com.immersivecinematics.immersive_cinematics.math;

/**
 * spring — 二阶阻尼弹簧（{@code x'' = −k·e − 2ζω·x'}，{@code e = x − target}、{@code ω = √k}）。
 *
 * 参数：{@code stiffness} = 刚度 k（1/s²，缺省 8.0，范围 1e-3 ~ 1e3）；
 * {@code damping} = 阻尼比 ζ（无量纲，缺省 0.75，范围 0 ~ 10；ζ &lt; 1 欠阻尼、ζ = 1 临界、ζ &gt; 1 过阻尼）；
 * {@code initial_velocity} = 初速度（单位/秒，缺省 0，范围 ±1e4）。
 *
 * 步进 = 误差 e 的三种阻尼情形解析解（闭式，帧率无关：分段推进 = 整段推进，误差 ≤ 1e-9 相对）——
 * {@code x ← x + (e(dt) − e(0))}、{@code v ← e'(dt)}；ANGLE 域的 e 取最短角差（跨 ±180° 不绕远路）。
 *
 * 状态：每分量 (当前值, 速度)，初值 = 首个输入 + {@code initial_velocity}（当前值 = 首个输入、速度 = initial_velocity）；
 * 输出 = 当前值（ANGLE 不归一）。dt 上限 {@link MathModelInstance#MAX_DT}；实例状态所有权：实例独占；定义线程安全。
 */
public final class SpringModel implements MathModel {

    public static final String TYPE = "spring";
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

    /** 刚度 / 阻尼比 / 初速度越界或 NaN 时按 {@code clamp*} 钳制（各自缺省见常量）。 */
    public SpringModel(ValueDomain domain, double stiffness, double damping, double initialVelocity) {
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

        Instance(int arity, double stiffness, double damping, double initialVelocity, boolean angle) {
            super(arity);
            this.stiffness = stiffness;
            this.damping = damping;
            this.initialVelocity = initialVelocity;
            this.angle = angle;
            this.velocity = new double[arity];
        }

        @Override
        protected void seed(double[] in) {
            System.arraycopy(in, 0, state, 0, state.length);
            java.util.Arrays.fill(velocity, initialVelocity);
        }

        @Override
        protected void advance(double dt, double[] target) {
            if (damping < 1.0 - CRITICAL_EPS) {
                advanceUnderdamped(dt, target);
            } else if (damping > 1.0 + CRITICAL_EPS) {
                advanceOverdamped(dt, target);
            } else {
                advanceCritical(dt, target);
            }
        }

        /** 欠阻尼（ζ &lt; 1）：{@code ωd = ω√(1−ζ²)}，位移包络 = e^(−ζωt)·(cos ωd t, sin ωd t)。 */
        private void advanceUnderdamped(double dt, double[] target) {
            double omega = Math.sqrt(stiffness);
            double zetaOmega = damping * omega;
            double omegaD = omega * Math.sqrt(1.0 - damping * damping);
            double decay = Math.exp(-zetaOmega * dt);
            double cos = Math.cos(omegaD * dt);
            double sin = Math.sin(omegaD * dt);
            for (int i = 0; i < state.length; i++) {
                double e0 = displacement(i, target[i]);
                double v0 = velocity[i];
                double e1 = decay * (e0 * cos + (v0 + zetaOmega * e0) / omegaD * sin);
                double v1 = decay * (v0 * cos - (omega * omega * e0 + zetaOmega * v0) / omegaD * sin);
                state[i] += e1 - e0;
                velocity[i] = v1;
            }
        }

        /** 临界阻尼（ζ = 1）：位移 = e^(−ωt)·(e₀ + (v₀ + ω e₀)·t)。 */
        private void advanceCritical(double dt, double[] target) {
            double omega = Math.sqrt(stiffness);
            double decay = Math.exp(-omega * dt);
            for (int i = 0; i < state.length; i++) {
                double e0 = displacement(i, target[i]);
                double v0 = velocity[i];
                double e1 = decay * (e0 + (v0 + omega * e0) * dt);
                double v1 = decay * (v0 - omega * (v0 + omega * e0) * dt);
                state[i] += e1 - e0;
                velocity[i] = v1;
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
                double e0 = displacement(i, target[i]);
                double v0 = velocity[i];
                double c1 = (v0 - r2 * e0) / denominator;
                double c2 = e0 - c1;
                state[i] += (c1 * decay1 + c2 * decay2) - e0;
                velocity[i] = c1 * r1 * decay1 + c2 * r2 * decay2;
            }
        }

        /** 位移 e = 当前值 − 目标；ANGLE 域取最短角差（度，跨 ±180° 不绕远路）。 */
        private double displacement(int index, double target) {
            return angle ? -AngleUtil.delta(state[index], target) : state[index] - target;
        }
    }
}
