package com.immersivecinematics.immersive_cinematics.script.rule.runtime;

import com.immersivecinematics.immersive_cinematics.script.rule.RuleBinding;

/**
 * 一条绑定在<b>一次片段执行</b>内的运行态（{@code ROUND-I-4-MAP} §2.3）。
 *
 * <p>对象在 {@link RuleRuntime#sync} 时按绑定预分配、跨执行复用（{@link #beginExecution} 只重置
 * 基本类型字段）；帧内不新建。{@code cooldownUntil} 不随执行重置——冷却按播放时钟跨执行保留
 * （「once / cooldown：once=true 且已成功 → 不再触发；elapsed < cooldownUntil → 不触发」）。</p>
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code executionId} = 本激活对应的片段执行身份（{@code 0} = 尚无执行）；</li>
 *   <li>{@code armed} = 时间门已首次满足（{@code gateReachedAtReal} 的时刻有效）；</li>
 *   <li>{@code started} = 本执行的片段开始事实已记录；</li>
 *   <li>{@code succeeded} / {@code timedOut} / {@code finished} = 判定成功 / 超时 / 终态；</li>
 *   <li>{@code edgeTrue} = 上一帧条件真值（{@code enter} 上升沿用）；{@code dwellAccum} = 驻留累计秒；</li>
 *   <li>{@code holdAcquired} = 本激活当前是否经账本持有实例。</li>
 * </ul>
 *
 * <p>线程契约：仅客户端主线程。读写方法均为包内可见——运行态不是脚本数据，不对外暴露。</p>
 */
public final class RuleActivation {

    private RuleBinding binding;
    private long executionId;
    private int executionIndex = -1;
    private boolean started;
    private boolean succeeded;
    private boolean timedOut;
    private boolean finished;
    private boolean edgeTrue;
    private double dwellAccum;
    private double cooldownUntil;
    private double gateReachedAtReal;
    private boolean holdAcquired;
    private boolean armed;

    /** 绑定到一条绑定（{@link RuleRuntime#sync} 时一次）。 */
    void bind(RuleBinding binding) {
        this.binding = binding;
    }

    /** 进入一次新执行：重置本执行的判定状态，{@code cooldownUntil} 保留。 */
    void beginExecution(long executionId, int executionIndex) {
        this.executionId = executionId;
        this.executionIndex = executionIndex;
        this.started = false;
        this.succeeded = false;
        this.timedOut = false;
        this.finished = false;
        this.edgeTrue = false;
        this.dwellAccum = 0.0;
        this.gateReachedAtReal = 0.0;
        this.holdAcquired = false;
        this.armed = false;
    }

    /** 退出执行（片段尚未开始 / 引擎重建）：保留冷却，其余归零。 */
    void clearExecution() {
        beginExecution(0L, -1);
        this.started = false;
    }

    RuleBinding binding() {
        return binding;
    }

    long executionId() {
        return executionId;
    }

    int executionIndex() {
        return executionIndex;
    }

    boolean started() {
        return started;
    }

    void setStarted(boolean started) {
        this.started = started;
    }

    boolean succeeded() {
        return succeeded;
    }

    void setSucceeded(boolean succeeded) {
        this.succeeded = succeeded;
    }

    boolean timedOut() {
        return timedOut;
    }

    void setTimedOut(boolean timedOut) {
        this.timedOut = timedOut;
    }

    boolean finished() {
        return finished;
    }

    void setFinished(boolean finished) {
        this.finished = finished;
    }

    boolean edgeTrue() {
        return edgeTrue;
    }

    void setEdgeTrue(boolean edgeTrue) {
        this.edgeTrue = edgeTrue;
    }

    double dwellAccum() {
        return dwellAccum;
    }

    void addDwell(double delta) {
        this.dwellAccum += delta;
    }

    void resetDwell() {
        this.dwellAccum = 0.0;
    }

    double cooldownUntil() {
        return cooldownUntil;
    }

    void setCooldownUntil(double cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    double gateReachedAtReal() {
        return gateReachedAtReal;
    }

    /** 记录时间门首次满足的实时时刻（{@code armed} 同时置真）。 */
    void arm(double nowReal) {
        this.armed = true;
        this.gateReachedAtReal = nowReal;
    }

    boolean armed() {
        return armed;
    }

    boolean holdAcquired() {
        return holdAcquired;
    }

    void setHoldAcquired(boolean holdAcquired) {
        this.holdAcquired = holdAcquired;
    }

    @Override
    public String toString() {
        return String.format("RuleActivation{binding=%s, execution=%d/%d, gateArmed=%s, "
                        + "succeeded=%s, timedOut=%s, hold=%s}",
                binding != null ? binding.id() : "<none>", executionId, executionIndex,
                armed, succeeded, timedOut, holdAcquired);
    }
}
