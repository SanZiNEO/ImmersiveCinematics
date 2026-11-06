package com.immersivecinematics.immersive_cinematics.script.execution;

/**
 * 执行账本的不可变深拷贝快照（分支失败 fallback 用）：逐字段复制全部执行记录 + 当时的播放头读数。
 * 快照建成后不再被账本改写；{@link ClipExecutionLedger#restore(BranchSnapshot)} 再把字段复制回账本。
 */
public final class BranchSnapshot {

    private final ClipExecution[] executions;
    private final double lastHead;

    BranchSnapshot(ClipExecution[] executions, double lastHead) {
        this.executions = new ClipExecution[executions.length];
        for (int i = 0; i < executions.length; i++) {
            this.executions[i] = executions[i].copy();
        }
        this.lastHead = lastHead;
    }

    /** 记录数（= timeline 扁平片段数 + 脚本级独立片段数）。 */
    public int size() {
        return executions.length;
    }

    /** 第 {@code flatIndex} 条记录（快照内的只读副本）。 */
    public ClipExecution at(int flatIndex) {
        return executions[flatIndex];
    }

    /** 快照时的播放头读数（秒）。 */
    public double lastHead() {
        return lastHead;
    }
}
