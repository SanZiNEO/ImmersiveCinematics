package com.immersivecinematics.immersive_cinematics.math;

/**
 * 曲线模型 — 无状态逐分量变换 {@code out[i] = curve(in[i])}
 * （linear / smoothstep / ease_in / ease_out / ease_in_out / cubic_bezier 共用实现）。
 *
 * 单位与定义域：输入 = 归一化进度；曲线族按 [0,1] 求值、越界处置见 {@link Curves} / {@link CubicBezierCurve}。
 * 忽略 t 与 dt；smoothstep / ease_* 的越界输入由 {@code Curves.clamp01} 钳到 [0,1]（±Inf → 1.0 / 0.0，NaN 原样透传），
 * {@code linear} 恒等不钳制——非有限值最终由调用层守门处置。
 *
 * 实例无可变状态（{@code reset} 为 no-op）、可跨调用点共享同一 {@link CurveFn}（纯函数）；step 零分配。
 */
public final class CurveModel implements MathModel {

    /** 注册名；v1 无生产读取点，仅 {@link #type()} 回读（冻结规格 / 诊断用途）。 */
    private final String type;
    private final ValueDomain domain;
    private final CurveFn curve;

    public CurveModel(String type, ValueDomain domain, CurveFn curve) {
        this.type = type;
        this.domain = domain;
        this.curve = curve;
    }

    @Override
    public String type() {
        return type;
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
        return false;
    }

    @Override
    public MathModelInstance instantiate() {
        return new CurveInstance(domain.channels(), curve);
    }

    private static final class CurveInstance implements MathModelInstance {

        private final int arity;
        private final CurveFn curve;

        CurveInstance(int arity, CurveFn curve) {
            this.arity = arity;
            this.curve = curve;
        }

        @Override
        public void step(double t, double dt, double[] in, double[] out) {
            for (int i = 0; i < arity; i++) {
                out[i] = curve.at(in[i]);
            }
        }

        @Override
        public void reset() {
            // 无状态：无需归位
        }
    }
}
