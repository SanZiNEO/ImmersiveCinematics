package com.immersivecinematics.immersive_cinematics.script.rule.runtime;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleBinding;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleRelease;

import java.util.List;

/**
 * 一条已承诺、尚未提交的动作（{@code ROUND-I-4-MAP} §1.4 / I4-5）：判定成功（或超时）时建立，
 * 释放条件满足时由 {@link RuleRuntime} 出队提交。
 *
 * <p>不可变：字段在入队帧一次写定，此后只读。三个「调度时」读数把释放条件钉在<b>本次承诺</b>上，
 * 而不是钉在「当前帧」上：</p>
 * <ul>
 *   <li>{@link #requestedSeq} = 调度时的 {@link RuleFactStore#sequence()}：事实类释放条件必须
 *       <b>晚于</b>它才成立（不消费承诺之前的历史事实）；</li>
 *   <li>{@link #requestedHead} / {@link #requestedExecutionId} = 调度时的播放头与目标片段执行身份：
 *       区分「本执行已到达」与「下一次执行开始」；</li>
 *   <li>{@link #requestedReal} = 调度时的真实时钟秒：{@code delay} 按真实时间计（挂起不冻结）。</li>
 * </ul>
 *
 * <p>分配契约：只在入队帧创建（非稳态帧）；出队判定不新建对象。线程契约：仅客户端主线程。</p>
 */
final class PendingAction {

    /** 承诺动作的绑定（动作作用对象 = 该绑定的目标片段 / 本实例）。 */
    final RuleBinding binding;

    /** 待提交的动作列表（{@code actions} 或 {@code on_timeout}；脚本解析后的不可变列表）。 */
    final List<JsonObject> actions;

    /** 释放条件（非 {@code null}；立即提交的动作不入队）。 */
    final RuleRelease release;

    /** 调度时的播放头（{@code ClipExecutionLedger#lastHead()}）。 */
    final double requestedHead;

    /** 调度时的真实时钟秒（{@code delay} 的计时起点）。 */
    final double requestedReal;

    /** 调度时的事实序号（事实类释放条件的下界）。 */
    final long requestedSeq;

    /** 调度时目标片段的执行身份（无目标 → {@code -1}）。 */
    final long requestedExecutionId;

    /** 是否 {@code on_timeout} 动作（同一调度机制，只标记来源）。 */
    final boolean timedOut;

    PendingAction(RuleBinding binding, List<JsonObject> actions, RuleRelease release,
                  double requestedHead, double requestedReal, long requestedSeq,
                  long requestedExecutionId, boolean timedOut) {
        this.binding = binding;
        this.actions = actions;
        this.release = release;
        this.requestedHead = requestedHead;
        this.requestedReal = requestedReal;
        this.requestedSeq = requestedSeq;
        this.requestedExecutionId = requestedExecutionId;
        this.timedOut = timedOut;
    }

    @Override
    public String toString() {
        return "PendingAction{" + binding.id() + ", on="
                + (release != null ? release.kind() : "none") + ", timedOut=" + timedOut + "}";
    }
}
