package com.immersivecinematics.immersive_cinematics.math;

/**
 * 模型定义 — 不可变数据，可被多个调用点共享（一处定义、多处调用）。
 *
 * 由 {@code script/schema/MathModelRegistry} 按 JSON 的 {@code type} 名创建（JSON → 定义的唯一入口）；
 * 定义线程安全、不持有调用点状态，每个调用点各自 {@link #instantiate()} 一份实例。
 * 输入 / 输出同域，通道数 = {@link ValueDomain#channels()}；{@code inputArity() = 0} = 无输入源模型。
 */
public interface MathModel {

    /** 注册名（snake_case，与 JSON 的 {@code type} 一致）；当前无生产读取点，只用于冻结规格 / 诊断。 */
    String type();

    /** 值域（见 {@link ValueDomain}）；当前无生产读取点，只用于冻结规格 / 诊断。 */
    ValueDomain domain();

    /** 输入通道数；= {@code domain().channels()}，0 = 无输入源。 */
    int inputArity();

    /** 输出通道数；= {@link #inputArity()}。 */
    int outputArity();

    /** true = 输出依赖历史（需要 dt 与实例状态）；无状态模型的 {@code step} 忽略 dt、{@code reset} 为 no-op。 */
    boolean stateful();

    /**
     * 定义 → 实例（每个调用点一份，状态不共享）。
     *
     * 分配状态缓冲，只在该通道创建 / 重建实例时调用，不在逐帧路径。
     */
    MathModelInstance instantiate();
}
