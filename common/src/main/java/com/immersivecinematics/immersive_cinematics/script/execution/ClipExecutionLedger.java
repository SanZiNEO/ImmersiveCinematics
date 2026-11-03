package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放实例级执行账本：每个扁平片段（轨道序 → 轨道内片段序 → 脚本级独立片段序）一条
 * {@link ClipExecution}，由播放头推进重导 / 推进执行事实。执行次数、执行身份、局部采样时间与
 * 完成事实的唯一真源。
 *
 * <p>独立片段（{@link CinematicScript#getStandaloneClips()}）没有默认放置：记录以
 * {@link ClipExecution#markStandalone()} 的「未启动」态存在，播放头经过不使其活跃、不计入
 * 完成度 / 内容末端；只有 {@link #startBranch(Clip, double, double)} 启动后（{@code branchEntry}）
 * 才按正常执行语义计入。</p>
 *
 * <p>默认放置：{@code seriesStart = clip.startTime}；有限窗口
 * {@code windowEnd = seriesStart + duration × (loop ? max(1, count) : 1)}。每次 {@link #reset(double)}
 * 分配全新执行身份；{@link #advance(double)} 只接受不回退的播放头，跨执行边界分配新身份。
 * 无限执行（{@code duration<0} 或无限次数）没有有限窗口末端、永不 done。</p>
 *
 * <p>分配契约：构造 / reset 之外的帧内路径（{@link #advance(double)} / {@link #isActive} /
 * {@link #localTime}）只读既有记录，不分配。</p>
 */
public final class ClipExecutionLedger {

    private final ClipExecution[] executions;
    private final Map<Clip, ClipExecution> indexByClip;
    private long nextExecutionId;
    private double lastHead;

    public ClipExecutionLedger(CinematicScript script) {
        List<ClipExecution> list = new ArrayList<>();
        int flat = 0;
        if (script != null && script.getTimeline() != null) {
            for (TimelineTrack track : script.getTimeline().getTracks()) {
                for (Clip clip : track.getClips()) {
                    list.add(new ClipExecution(clip, flat++));
                }
            }
        }
        if (script != null) {
            // 独立片段接在 timeline 铺平序之后；旧规则的扁平索引只覆盖 timeline，因此不受影响。
            for (Clip clip : script.getStandaloneClips()) {
                ClipExecution execution = new ClipExecution(clip, flat++);
                execution.markStandalone();
                list.add(execution);
            }
        }
        this.executions = list.toArray(new ClipExecution[0]);
        this.indexByClip = new HashMap<>(Math.max(4, this.executions.length * 2));
        for (ClipExecution execution : this.executions) {
            indexByClip.put(execution.clip(), execution);
        }
        reset(0.0);
    }

    /** 分配实例内单调递增的执行身份（绝不复用）。 */
    long allocateId() {
        return ++nextExecutionId;
    }

    /**
     * 按播放头 {@code head} 重导全部记录（默认放置），并为每条记录分配全新执行身份。
     * {@code head < windowStart} → pending；{@code head >= windowEnd} → done；否则按方向映射局部时间。
     */
    public void reset(double head) {
        this.lastHead = head;
        for (ClipExecution execution : executions) {
            execution.resetDefault(head, ++nextExecutionId);
        }
    }

    /**
     * 把全部记录推进到 {@code head}（必须 {@code >= lastHead}；回退由调用方 {@link #reset(double)}）。
     * 跨执行边界（一帧跨多圈）一次补齐，只保留最终执行的身份。
     *
     * @throws IllegalArgumentException {@code head < lastHead}
     */
    public void advance(double head) {
        if (head < lastHead) {
            throw new IllegalArgumentException("执行账本不能回退：head=" + head + " < lastHead=" + lastHead);
        }
        if (head == lastHead) return;
        this.lastHead = head;
        for (ClipExecution execution : executions) {
            execution.advanceTo(head, this);
        }
    }

    public ClipExecution executionOf(Clip clip) {
        return clip == null ? null : indexByClip.get(clip);
    }

    public ClipExecution at(int flatIndex) {
        return flatIndex >= 0 && flatIndex < executions.length ? executions[flatIndex] : null;
    }

    public int size() {
        return executions.length;
    }

    public double lastHead() {
        return lastHead;
    }

    /** 片段在 {@code head} 处是否活跃：{@code [windowStart, windowEnd)}；无限自 {@code windowStart} 起永续。 */
    public boolean isActive(Clip clip, double head) {
        ClipExecution execution = executionOf(clip);
        if (execution == null) return false;
        if (isPendingStandalone(execution)) return false; // 独立片段没有默认放置
        return head >= execution.windowStart
                && (!execution.finiteWindowEnd || head < execution.windowEnd);
    }

    /** 片段在 {@code head} 处当前执行的局部时间；未知片段 → {@code 0}。 */
    public double localTime(Clip clip, double head) {
        ClipExecution execution = executionOf(clip);
        return execution != null ? execution.localTime : 0.0;
    }

    /** 片段活跃窗起点（默认放置 = 片段起点；分支入口 = 跳转时刻）。 */
    public double windowStart(Clip clip) {
        ClipExecution execution = executionOf(clip);
        return execution != null ? execution.windowStart : clip.getStartTime();
    }

    public boolean hasFiniteWindowEnd(Clip clip) {
        ClipExecution execution = executionOf(clip);
        return execution != null ? execution.finiteWindowEnd : clip.hasFiniteWindowEnd();
    }

    /**
     * 片段有限展开窗口末端（不含）。
     *
     * @throws IllegalStateException 片段无限（调用方必须先判 {@link #hasFiniteWindowEnd(Clip)}）
     */
    public double windowEnd(Clip clip) {
        ClipExecution execution = executionOf(clip);
        if (execution == null) return clip.getWindowEnd();
        if (!execution.finiteWindowEnd) {
            throw new IllegalStateException("无限执行没有有限窗口末端：" + clip);
        }
        return execution.windowEnd;
    }

    /** 全部记录是否都有有限展开末端（空脚本 = true；未启动的独立片段不计入）。 */
    public boolean hasFiniteContentEnd() {
        for (ClipExecution execution : executions) {
            if (isPendingStandalone(execution)) continue;
            if (execution.infinite || !execution.finiteWindowEnd) return false;
        }
        return true;
    }

    /**
     * 全部片段展开窗口末端的最大值（空脚本 = 0；未启动的独立片段不计入）。
     *
     * @throws IllegalStateException 存在无限执行（调用方必须先判 {@link #hasFiniteContentEnd()}）
     */
    public double contentEnd() {
        double end = 0.0;
        for (ClipExecution execution : executions) {
            if (isPendingStandalone(execution)) continue;
            if (execution.infinite || !execution.finiteWindowEnd) {
                throw new IllegalStateException("存在无限执行，内容没有有限末端");
            }
            if (execution.windowEnd > end) end = execution.windowEnd;
        }
        return end;
    }

    /** 全部片段是否已执行完（空脚本 = true；未启动的独立片段不计入）。 */
    public boolean allDone() {
        for (ClipExecution execution : executions) {
            if (isPendingStandalone(execution)) continue;
            if (!execution.done()) return false;
        }
        return true;
    }

    /** 尚未执行完的片段数（空脚本 = 0；未启动的独立片段不计入）。 */
    public int remainingContent() {
        int remaining = 0;
        for (ClipExecution execution : executions) {
            if (isPendingStandalone(execution)) continue;
            if (!execution.done()) remaining++;
        }
        return remaining;
    }

    /**
     * 把该片段替换为分支入口执行：{@code seriesStart = launch − entryLocal}、
     * {@code windowStart = executionStart = launch}、index=0、FORWARD、{@code branchEntry=true}。
     * 有限窗口末端仍 {@code seriesStart + duration × N}。播放头同步到跳转时刻。
     */
    public ClipExecution startBranch(Clip clip, double executionStart, double entryLocalTime) {
        ClipExecution execution = executionOf(clip);
        if (execution == null) return null;
        execution.startBranch(executionStart, entryLocalTime, ++nextExecutionId);
        this.lastHead = executionStart;
        return execution;
    }

    /** 结束分支：按当前播放头重导该片段的默认放置（分配新身份，{@code branchEntry=false}）。 */
    public void endBranch(Clip clip) {
        ClipExecution execution = executionOf(clip);
        if (execution == null) return;
        execution.resetDefault(lastHead, ++nextExecutionId);
    }

    /** 深拷贝快照（含全部记录字段与播放头）。 */
    public BranchSnapshot snapshot() {
        return new BranchSnapshot(executions, lastHead);
    }

    /** 按快照逐字段恢复记录与播放头（失败 fallback 用；不重放内容）。 */
    public void restore(BranchSnapshot snapshot) {
        if (snapshot == null) return;
        int count = Math.min(executions.length, snapshot.size());
        for (int i = 0; i < count; i++) {
            executions[i].copyFrom(snapshot.at(i));
        }
        this.lastHead = snapshot.lastHead();
    }

    /** 未启动的脚本级独立片段：不在主线上，不参与活跃判定 / 完成度 / 内容末端。 */
    private static boolean isPendingStandalone(ClipExecution execution) {
        return execution.standalone && !execution.branchEntry;
    }
}
