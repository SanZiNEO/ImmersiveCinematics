package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 分支片段出口的运行期驱动：单分支、无嵌套/调用栈。
 *
 * <p>正常完成：{@link #poll()} 观察到当前分支 target 的 {@link ClipExecution#done()} 后，
 * 结束分支并按「入口覆盖 &gt; 片段默认 &gt; CONTINUE」解析出口；GOTO 以链式方式启动新目标
 * （新目标用自身默认策略，不带入口覆盖）。{@link ClipExitKind#END_EXECUTION} 与
 * {@link ClipExitKind#CONTINUE} 本轮行为相同：结束分支回主线；差异留给后续序列轮。</p>
 *
 * <p>失败：{@link #fail(Clip, String)} 恢复 launch 时的账本快照并 seek 回快照播放头，
 * 只回调 {@link ClipBranchHost#onBranchFailed}；绝不执行正常完成策略（不 GOTO、不 END_SCRIPT）。
 * 正常与失败因此可严格区分。</p>
 */
public final class ClipBranchController {

    private final ClipExecutionLedger ledger;
    private final ClipExitResolver resolver;
    private final ClipBranchHost host;

    private boolean branching;
    private Clip target;
    private ClipExitPolicy entryOverride;
    private BranchSnapshot launchSnapshot;

    public ClipBranchController(ClipExecutionLedger ledger, ClipExitResolver resolver, ClipBranchHost host) {
        if (ledger == null) {
            throw new IllegalArgumentException("执行账本不能为 null");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("出口解析器不能为 null");
        }
        if (host == null) {
            throw new IllegalArgumentException("分支宿主不能为 null");
        }
        this.ledger = ledger;
        this.resolver = resolver;
        this.host = host;
    }

    /**
     * 以 {@code entryLocalTime} 启动 {@code target} 的分支入口。
     *
     * @param entryOverride 入口显式出口策略（优先于默认）；{@code null} 表示用默认
     * @return 是否成功启动；已处于分支中、target 为 null 或不在账本、入口局部时间非法
     *         （非有限、{@code <0}、有限 target 上 {@code > duration}）→ false 且不改变现有分支
     */
    public boolean launch(Clip target, double entryLocalTime, ClipExitPolicy entryOverride) {
        if (branching || target == null) {
            return false;
        }
        ClipExecution execution = ledger.executionOf(target);
        if (execution == null) {
            return false;
        }
        if (!Double.isFinite(entryLocalTime) || entryLocalTime < 0.0) {
            return false;
        }
        if (!target.isEffectivelyInfinite() && entryLocalTime > target.getDuration()) {
            return false;
        }

        BranchSnapshot snapshot = ledger.snapshot();
        this.branching = true;
        this.target = target;
        this.entryOverride = entryOverride;
        this.launchSnapshot = snapshot;

        ledger.startBranch(target, host.now(), entryLocalTime);
        host.onBranchStarted(target, execution);
        return true;
    }

    /** 推进分支状态机；非分支中或当前 target 尚未完成时无动作。 */
    public void poll() {
        if (!branching) {
            return;
        }
        Clip current = target;
        ClipExecution execution = ledger.executionOf(current);
        if (execution == null || !execution.done()) {
            return;
        }

        // 先清空分支状态：GOTO 链通过 launch 重新置位，避免重入时误判「已分支」。
        ClipExitPolicy override = entryOverride;
        this.branching = false;
        this.target = null;
        this.entryOverride = null;
        this.launchSnapshot = null;

        ClipExitPolicy policy = resolver.resolve(current, override);
        switch (policy.kind()) {
            case CONTINUE, RETURN, END_EXECUTION -> ledger.endBranch(current);
            case GOTO -> {
                ledger.endBranch(current);
                launch(policy.target(), policy.entryLocalTime(), null);
            }
            case END_SCRIPT -> {
                ledger.endBranch(current);
                host.requestScriptEnd();
            }
        }
    }

    /**
     * 分支失败：恢复 launch 快照（执行身份 / 局部时间 / 完成事实 / 播放头），只回调失败。
     *
     * @return 是否处理；不在分支中或 {@code target} 不是当前分支 target → false 且无副作用
     */
    public boolean fail(Clip target, String reason) {
        if (!branching || this.target != target) {
            return false;
        }
        BranchSnapshot snapshot = this.launchSnapshot;

        this.branching = false;
        this.target = null;
        this.entryOverride = null;
        this.launchSnapshot = null;

        ledger.restore(snapshot);
        host.seekTo(snapshot.lastHead());
        host.onBranchFailed(target, reason);
        return true;
    }

    /** 是否处于分支中（GOTO 链保持 true 直到链尾结束）。 */
    public boolean isBranching() {
        return branching;
    }

    /** 当前分支 target；不在分支中 → {@code null}。 */
    public Clip currentTarget() {
        return branching ? target : null;
    }
}
