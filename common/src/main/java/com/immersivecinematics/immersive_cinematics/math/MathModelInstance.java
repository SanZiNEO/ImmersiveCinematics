package com.immersivecinematics.immersive_cinematics.math;

/**
 * 模型实例 — 每个调用点一份，持有该调用点的状态。
 *
 * 线程与分配契约：实例不线程安全（单调用点 / 单线程独占）；{@link #step} 每渲染帧一次、零分配
 * （不 new / 不装箱 / 不拼串）；{@code in} / {@code out} 由调用方复用，本方法不保留其引用。
 *
 * 时间语义：{@code t} = 单调秒（模型层不读时钟）；{@code dt} = 距上一步的秒差 —— 0 合法
 * （首步 / 暂停帧：状态与输出保持），负值按 0，超过 {@link #MAX_DT} 按 {@link #MAX_DT} 推进。
 * 有状态模型的初值 = 首个输入（无起始瞬态）。
 */
public interface MathModelInstance {

    /** 单步 dt 上限（秒）：限制单步目标跳变幅度（闭式解析步进无过冲），超出按此值推进。 */
    double MAX_DT = 0.25;

    /** 推进一步（每帧一次）；{@code in} / {@code out} 长度 = 定义的输入 / 输出通道数，调用方复用。 */
    void step(double t, double dt, double[] in, double[] out);

    /** 状态归位；reset 后首次 step 以输入为初值（无起始瞬态）。无状态实例 no-op。 */
    void reset();
}
