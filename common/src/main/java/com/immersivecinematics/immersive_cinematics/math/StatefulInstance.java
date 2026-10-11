package com.immersivecinematics.immersive_cinematics.math;

/**
 * 有状态实例骨架 — 播种 / dt 语义 / 输出拷贝的公共模板（每分量独立状态）。
 *
 * 状态所有权：{@code state} 由本实例独占（构造时按通道数分配一次），不跨实例共享。
 * 播种 = 首个输入即初值（该步不推进 = 无起始瞬态）；之后 dt ≤ 0 不推进（暂停 / 负值 → 状态与输出保持），
 * dt &gt; {@link MathModelInstance#MAX_DT} 按上限推进。step 零分配。
 */
abstract class StatefulInstance implements MathModelInstance {

    /** 每分量输出状态（初值 = 首个输入）；长度 = 通道数。 */
    final double[] state;

    private boolean seeded;

    StatefulInstance(int arity) {
        this.state = new double[arity];
    }

    @Override
    public final void step(double t, double dt, double[] in, double[] out) {
        if (!seeded) {
            seed(in);
            seeded = true;
        } else if (dt > 0.0) {
            advance(Math.min(dt, MAX_DT), in);
        }
        System.arraycopy(state, 0, out, 0, state.length);
    }

    @Override
    public final void reset() {
        seeded = false;
    }

    /** 播种：以首个输入为初值（子类按自身状态维度设置）。 */
    protected abstract void seed(double[] in);

    /**
     * 闭式推进 {@code dt} 秒（dt ∈ (0, MAX_DT]），{@code target} = 本步输入。
     *
     * 更新 {@link #state}（必要时含子类附加状态）；不得分配。
     */
    protected abstract void advance(double dt, double[] target);
}
