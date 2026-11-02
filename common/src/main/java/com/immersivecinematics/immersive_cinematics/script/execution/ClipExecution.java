package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 单片段当前执行记录（播放控制状态，不是对象采样器状态）：哪一次执行、本次执行的局部时间、方向、
 * 剩余次数与完成事实。字段只由同包 {@link ClipExecutionLedger} 写，外部只读。
 *
 * <p>语义：{@code executionIndex} 从 0 起；{@code executionLocalTime} 有限时恒在 {@code [0, duration]}，
 * {@code duration<0} 时只保证 {@code >=0}；{@code executionsRemaining} 是当前执行之后剩余次数
 * （无限 = -1）；{@code done = contentDone && executionsRemaining == 0}，无限恒 false。
 * {@code windowEnd} 只在 {@code hasFiniteWindowEnd()} 为真时有效。</p>
 */
public final class ClipExecution {

    private final Clip clip;
    private final int flatIndex;

    /** 本次执行身份（实例内单调递增；reset / seek / 跨执行边界分配新值，绝不复用）。 */
    long executionId;
    int executionIndex;
    /** 当前执行在实例时间轴上的起点（{@code seriesStart + executionIndex × duration}；分支入口 = 跳转时刻）。 */
    double executionStart;
    /** 当前执行的局部采样时间。 */
    double localTime;
    /** 单次执行时长（片段 {@code duration}）。 */
    double duration;
    LoopMode loopMode;
    /** 有效执行次数：非循环 = 1；有限循环 = N；无限 = -1。 */
    int loopCount;
    int executionsRemaining;
    boolean infinite;
    ExecutionDirection direction;
    boolean contentDone;
    boolean done;
    boolean branchEntry;
    /** 活跃窗起点（默认放置 = 片段起点；分支入口 = 跳转时刻）。 */
    double windowStart;
    boolean finiteWindowEnd;
    /** 有限展开窗口末端（不含）；{@link #finiteWindowEnd} 为假时无效。 */
    double windowEnd;
    /** 默认放置的 series 起点（= 片段起点）；分支入口 = 跳转时刻 − 入口局部时间。 */
    double seriesStart;
    /** 分支入口局部时间（默认 0）。 */
    double localOrigin;

    ClipExecution(Clip clip, int flatIndex) {
        this.clip = clip;
        this.flatIndex = flatIndex;
    }

    // ── 只读访问器 ──

    public Clip clip() { return clip; }
    public int flatIndex() { return flatIndex; }
    public long executionId() { return executionId; }
    public int executionIndex() { return executionIndex; }
    public double executionStart() { return executionStart; }
    public double localTime() { return localTime; }
    public double duration() { return duration; }
    public LoopMode loopMode() { return loopMode; }
    public int loopCount() { return loopCount; }
    public int executionsRemaining() { return executionsRemaining; }
    public boolean infinite() { return infinite; }
    public ExecutionDirection direction() { return direction; }
    public boolean contentDone() { return contentDone; }
    public boolean done() { return done; }
    public boolean branchEntry() { return branchEntry; }
    public double windowStart() { return windowStart; }
    public boolean hasFiniteWindowEnd() { return finiteWindowEnd; }
    public double windowEnd() { return windowEnd; }

    // ── 包内写入口（只由账本调用） ──

    /** 按默认放置重导到 {@code head}（片段起点为 series 起点；{@code branchEntry=false}）。 */
    void resetDefault(double head, long id) {
        this.seriesStart = clip.getStartTime();
        this.localOrigin = 0.0;
        this.branchEntry = false;
        this.duration = clip.getDuration();
        this.loopMode = clip.getLoopModeValue();
        this.infinite = clip.isEffectivelyInfinite();
        this.windowStart = seriesStart;
        if (infinite) {
            this.loopCount = -1;
            this.finiteWindowEnd = false;
            this.windowEnd = 0.0;
        } else {
            this.loopCount = effectiveLoopCount();
            this.finiteWindowEnd = true;
            this.windowEnd = seriesStart + duration * loopCount;
        }
        this.executionId = id;
        applyHead(head);
    }

    /**
     * 推进到 {@code head}（调用方保证 {@code head >= 上次 head}）。
     * 跨执行边界时分配新身份（一帧跨多圈只保留最终执行的身份；中间执行从未被观察到）。
     */
    void advanceTo(double head, ClipExecutionLedger owner) {
        int previousIndex = executionIndex;
        applyHead(head);
        if (executionIndex != previousIndex) {
            this.executionId = owner.allocateId();
        }
    }

    /** 把本记录替换为分支入口执行（{@code launch} = 跳转时刻，{@code entryLocal} = 入口局部时间）。 */
    void startBranch(double launch, double entryLocal, long id) {
        this.duration = clip.getDuration();
        this.loopMode = clip.getLoopModeValue();
        this.infinite = clip.isEffectivelyInfinite();
        this.seriesStart = launch - entryLocal;
        this.localOrigin = entryLocal;
        this.branchEntry = true;
        this.windowStart = launch;
        if (infinite) {
            this.loopCount = -1;
            this.finiteWindowEnd = false;
            this.windowEnd = 0.0;
        } else {
            this.loopCount = effectiveLoopCount();
            this.finiteWindowEnd = true;
            this.windowEnd = seriesStart + duration * loopCount;
        }
        this.executionIndex = 0;
        this.executionStart = launch;
        this.executionId = id;
        this.direction = ExecutionDirection.FORWARD;
        this.localTime = clampLocal(entryLocal);
        this.executionsRemaining = infinite ? -1 : Math.max(0, loopCount - 1);
        this.contentDone = false;
        this.done = false;
    }

    /** 深拷贝（快照用；副本不再被账本改写）。 */
    ClipExecution copy() {
        ClipExecution copy = new ClipExecution(clip, flatIndex);
        copy.copyFrom(this);
        return copy;
    }

    /** 逐字段深拷贝（restore 用）。 */
    void copyFrom(ClipExecution other) {
        this.executionId = other.executionId;
        this.executionIndex = other.executionIndex;
        this.executionStart = other.executionStart;
        this.localTime = other.localTime;
        this.duration = other.duration;
        this.loopMode = other.loopMode;
        this.loopCount = other.loopCount;
        this.executionsRemaining = other.executionsRemaining;
        this.infinite = other.infinite;
        this.direction = other.direction;
        this.contentDone = other.contentDone;
        this.done = other.done;
        this.branchEntry = other.branchEntry;
        this.windowStart = other.windowStart;
        this.finiteWindowEnd = other.finiteWindowEnd;
        this.windowEnd = other.windowEnd;
        this.seriesStart = other.seriesStart;
        this.localOrigin = other.localOrigin;
    }

    // ── 内部 ──

    /** 有效执行次数：非循环 = 1；有限循环 = N（防御 0 → 1）；无限 = -1。 */
    private int effectiveLoopCount() {
        if (!clip.isExecutionLoop()) return 1;
        int count = clip.getLoopCount();
        return count > 0 ? count : 1;
    }

    private void applyHead(double head) {
        if (infinite) {
            applyInfinite(head);
            return;
        }
        if (head < seriesStart) {
            this.executionIndex = 0;
            this.direction = ExecutionDirection.of(loopMode, 0);
            this.localTime = 0.0;
            this.executionStart = seriesStart;
            this.executionsRemaining = Math.max(0, loopCount - 1);
            this.contentDone = false;
            this.done = false;
            return;
        }
        if (head >= windowEnd) {
            int lastIndex = Math.max(0, loopCount - 1);
            this.executionIndex = lastIndex;
            this.direction = ExecutionDirection.of(loopMode, lastIndex);
            this.localTime = direction == ExecutionDirection.FORWARD ? duration : 0.0;
            this.executionStart = seriesStart + lastIndex * duration;
            this.executionsRemaining = 0;
            this.contentDone = true;
            this.done = true;
            return;
        }
        double elapsed = head - seriesStart;
        int index = (int) Math.floor(elapsed / duration);
        if (index < 0) index = 0;
        if (index > loopCount - 1) index = loopCount - 1;
        this.executionIndex = index;
        this.direction = ExecutionDirection.of(loopMode, index);
        double offset = elapsed - index * duration;
        this.localTime = clampLocal(direction == ExecutionDirection.FORWARD ? offset : duration - offset);
        this.executionStart = seriesStart + index * duration;
        this.executionsRemaining = Math.max(0, loopCount - 1 - index);
        this.contentDone = false;
        this.done = false;
    }

    private void applyInfinite(double head) {
        this.executionsRemaining = -1;
        this.contentDone = false;
        this.done = false;
        if (!(duration > 0.0)) {
            // duration<0（旧无限时长哨兵）：单次执行，局部时间持续增长
            this.executionIndex = 0;
            this.direction = ExecutionDirection.FORWARD;
            this.executionStart = seriesStart;
            this.localTime = Math.max(0.0, head - seriesStart);
            return;
        }
        double elapsed = head - seriesStart;
        if (elapsed < 0.0) {
            this.executionIndex = 0;
            this.direction = ExecutionDirection.of(loopMode, 0);
            this.executionStart = seriesStart;
            this.localTime = 0.0;
            return;
        }
        double rawIndex = Math.floor(elapsed / duration);
        int index = rawIndex >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rawIndex;
        this.executionIndex = index;
        this.direction = ExecutionDirection.of(loopMode, index);
        double offset = elapsed - index * duration;
        this.localTime = clampLocal(direction == ExecutionDirection.FORWARD ? offset : duration - offset);
        this.executionStart = seriesStart + index * duration;
    }

    private double clampLocal(double value) {
        if (duration > 0.0) {
            return Math.max(0.0, Math.min(duration, value));
        }
        return Math.max(0.0, value);
    }
}
