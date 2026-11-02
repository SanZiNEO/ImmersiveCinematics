package com.immersivecinematics.immersive_cinematics.script.execution;

/**
 * 显式执行依赖的等待语义。
 *
 * <p>{@link #WAIT_START} 只要求前置片段已经进入执行；{@link #WAIT_COMPLETION} 要求前置片段
 * 走到自然完成点，因此无限执行的前置片段会让依赖方永不可达。</p>
 */
public enum DependencyKind {

    /** 前置片段开始执行后，依赖方即可继续。 */
    WAIT_START,

    /** 前置片段自然完成后，依赖方才能继续。 */
    WAIT_COMPLETION
}
