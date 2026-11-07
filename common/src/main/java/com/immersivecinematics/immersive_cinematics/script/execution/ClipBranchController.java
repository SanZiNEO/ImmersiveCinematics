package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 片段完成出口的唯一派发入口：单分支、无嵌套/调用栈。
 *
 * <p>主线片段（timeline 轨道放置）与分支 target（{@link #launch} 启动）完成时都走同一处
 * {@link #applyExit(Clip, ClipExecution)}：按「入口覆盖 &gt; 片段默认 &gt; CONTINUE」解析出口。
 * 主线完成 {@code CONTINUE} = 时间轴继续；分支 target 完成 {@code CONTINUE} 才结束分支回主线。
 * GOTO 以链式方式启动新目标（新目标用自身默认策略，不带入口覆盖）；
 * {@link ClipExitKind#END_EXECUTION} 与 {@link ClipExitKind#CONTINUE} 本轮行为相同：分支 target 上
 * 结束分支回主线，差异留给后续序列轮。</p>
 *
 * <p>完成事实只认 {@link ClipExecution#done()} 由 false→true 的那一帧：{@link #poll()} 扫描账本
 * 全部记录，按 {@code (clip, executionId)} 记忆。执行身份变化（{@code seek} /
 * {@link ClipExecutionLedger#reset(double)} / {@link ClipExecutionLedger#startBranch} /
 * {@link ClipExecutionLedger#endBranch}）只记基线，绝不合成完成事件 —— seek 跳过的片段不算完成。</p>
 *
 * <p>失败：{@link #fail(Clip, String)} 恢复 launch 时的账本快照并 seek 回快照播放头，
 * 只回调 {@link ClipBranchHost#onBranchFailed}；绝不执行正常完成策略（不 GOTO、不 END_SCRIPT）。
 * 正常与失败因此可严格区分。</p>
 */
public final class ClipBranchController {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ClipBranch");

    private final ClipExecutionLedger ledger;
    private final ClipExitResolver resolver;
    private final ClipBranchHost host;

    private boolean branching;
    private Clip target;
    private ClipExitPolicy entryOverride;
    private BranchSnapshot launchSnapshot;

    /** 与账本等长：每个扁平索引上次观察到的执行身份。 */
    private long[] seenId;
    /** 与账本等长：该执行身份是否已派发过完成事件。 */
    private boolean[] seenDone;

    /** 播放头回退告警是否已发过（每控制器一次，避免逐帧刷屏）。 */
    private boolean warnedHeadReorder;

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
        initTrackers();
    }

    /** 构造时按当前账本记基线：已完成的记录不会再派发一次。 */
    private void initTrackers() {
        int size = ledger.size();
        this.seenId = new long[size];
        this.seenDone = new boolean[size];
        for (int i = 0; i < size; i++) {
            ClipExecution execution = ledger.at(i);
            if (execution == null) {
                continue;
            }
            seenId[i] = execution.executionId();
            seenDone[i] = execution.done();
        }
    }

    /** 账本记录只增不减；万一实例换了更大的账本，新记录一律先记基线。 */
    private void ensureTrackers(int size) {
        if (size <= seenId.length) {
            return;
        }
        int oldSize = seenId.length;
        long[] ids = new long[size];
        boolean[] done = new boolean[size];
        System.arraycopy(seenId, 0, ids, 0, oldSize);
        System.arraycopy(seenDone, 0, done, 0, oldSize);
        this.seenId = ids;
        this.seenDone = done;
        for (int i = oldSize; i < size; i++) {
            ClipExecution execution = ledger.at(i);
            if (execution == null) {
                continue;
            }
            ids[i] = execution.executionId();
            done[i] = execution.done();
        }
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

        // 时间不变量（I2-B7）：执行起点不得早于账本已观察到的播放头。播放器 seek / 挂起恢复可能让
        // host.now() 落后于 lastHead；此时以 lastHead 为起点，账本绝不回退（advance 契约）。
        double now = host.now();
        double lastHead = ledger.lastHead();
        if (Double.isFinite(now) && now < lastHead && !warnedHeadReorder) {
            warnedHeadReorder = true;
            LOGGER.debug("分支 launch 播放头回退：now={} < lastHead={}，按 lastHead 启动", now, lastHead);
        }
        double launch = Double.isFinite(now) ? Math.max(now, lastHead) : lastHead;
        ledger.startBranch(target, launch, entryLocalTime);
        // 分支入口是全新的执行身份：此刻就记基线（startBranch 后 done 恒为 false），
        // 之后的完成才派发。否则「入口接近片尾、一帧内跨过完成点」会只记基线、永远不派发。
        int index = execution.flatIndex();
        if (index >= 0 && index < seenId.length) {
            seenId[index] = execution.executionId();
            seenDone[index] = execution.done();
        }
        host.onBranchStarted(target, execution);
        return true;
    }

    /**
     * 推进完成派发：扫描账本全部记录，主线片段与分支 target 用同一套判定。
     *
     * <p>每个 {@code (clip, executionId)} 只在 {@code done} 由 false→true 的那一帧派发一次；
     * 执行身份变化（seek / reset / endBranch / startBranch）只记基线，不合成完成事件。</p>
     */
    public void poll() {
        int size = ledger.size();
        ensureTrackers(size);
        for (int i = 0; i < size; i++) {
            ClipExecution execution = ledger.at(i);
            if (execution == null) {
                continue;
            }
            long executionId = execution.executionId();
            if (executionId != seenId[i]) {
                // 换了执行身份：上一身份的完成事实已作废，新身份从当前 done 记基线。
                seenId[i] = executionId;
                seenDone[i] = execution.done();
                continue;
            }
            if (execution.done() && !seenDone[i]) {
                seenDone[i] = true;
                applyExit(execution.clip(), execution);
            }
        }
    }

    /**
     * 完成派发单点：入口覆盖 &gt; 片段默认 &gt; CONTINUE。
     *
     * <p>{@code isBranchTarget} 同时决定入口覆盖是否生效、以及是否要结束分支；
     * 主线执行没有分支状态可清，{@code CONTINUE} / {@code RETURN} / {@code END_EXECUTION} 均无操作。</p>
     */
    private void applyExit(Clip clip, ClipExecution execution) {
        boolean isBranchTarget = branching && target == clip;
        ClipExitPolicy policy = resolver.resolve(clip, isBranchTarget ? entryOverride : null);
        LOGGER.debug("片段完成派发：clip={} executionId={} exit={} branchTarget={}",
                clip.getClipId(), execution.executionId(), policy.kind(), isBranchTarget);

        switch (policy.kind()) {
            case CONTINUE, RETURN, END_EXECUTION -> endBranchIfTarget(isBranchTarget, clip);
            case GOTO -> {
                endBranchIfTarget(isBranchTarget, clip);
                if (!launch(policy.target(), policy.entryLocalTime(), null)) {
                    ErrorLog.logRateLimited("Branch", "goto-skipped", 5000L,
                            "片段出口 goto 未启动（已有分支在跑或目标非法）：from=" + clip.getClipId()
                                    + " to=" + policy.target().getClipId());
                }
            }
            case END_SCRIPT -> {
                endBranchIfTarget(isBranchTarget, clip);
                host.requestScriptEnd();
            }
        }
    }

    /** 分支 target 完成：清分支状态并结束分支；主线执行无操作。 */
    private void endBranchIfTarget(boolean isBranchTarget, Clip clip) {
        if (!isBranchTarget) {
            return;
        }
        clearBranchState();
        ledger.endBranch(clip);
    }

    /** 清空分支状态（GOTO 链通过 {@link #launch} 重新置位）。 */
    private void clearBranchState() {
        this.branching = false;
        this.target = null;
        this.entryOverride = null;
        this.launchSnapshot = null;
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

        clearBranchState();

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
