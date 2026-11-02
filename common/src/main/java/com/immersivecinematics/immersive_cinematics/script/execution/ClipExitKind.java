package com.immersivecinematics.immersive_cinematics.script.execution;

/**
 * 分支片段正常完成后的出口动作。
 *
 * <p>{@link #CONTINUE} / {@link #RETURN} / {@link #END_EXECUTION} 本轮都表示「结束分支、回主线」：
 * 当前没有隐式序列或调用栈可回退，因此三者行为一致；{@link #END_EXECUTION} 与 {@link #CONTINUE}
 * 的差异留给后续序列轮（存在调用栈后，前者结束整个执行序列，后者只回到上一层）。</p>
 */
public enum ClipExitKind {
    /** 结束分支，回主线继续。 */
    CONTINUE,
    /** 结束当前分支并立刻以入口局部时间启动另一片段（链式，无栈）。 */
    GOTO,
    /** 结束分支，回主线（保留给调用栈语义）。 */
    RETURN,
    /** 结束当前执行序列（本轮等同 {@link #CONTINUE}）。 */
    END_EXECUTION,
    /** 结束分支并请求脚本整体结束。 */
    END_SCRIPT
}
