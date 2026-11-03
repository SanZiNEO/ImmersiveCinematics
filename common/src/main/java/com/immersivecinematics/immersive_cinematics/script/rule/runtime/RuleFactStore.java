package com.immersivecinematics.immersive_cinematics.script.rule.runtime;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleBinding;

import java.util.IdentityHashMap;

/**
 * 每实例一份的事实存储：按片段 / 绑定记录<b>最近一次执行 / 激活</b>的事实（{@code ROUND-I-4-MAP} §0.2-3）。
 *
 * <p>口径：
 * <ul>
 *   <li>片段事实：{@link #recordClipStarted} 在片段执行进入时记录（时间门之前也记录，见「门前采集」）；
 *       {@link #recordClipCompleted} 在本次执行完成时记录。新执行开始会清掉上一次执行的
 *       {@code completed}（事实作用域 = 最近一次执行，不做无界历史）。</li>
 *   <li>规则事实：{@link #recordRuleSucceeded} 在判定成功时记录，{@link #recordActionCompleted}
 *       在该绑定的动作提交完成时记录（成功 ≠ 动作完成）；新激活建立时由 {@link RuleRuntime} 调
 *       {@link #clearActivationFacts}（上一激活不再是「最近一次激活」）。</li>
 *   <li>序号：每次<b>事实变化</b>递增 {@link #sequence()}（同一执行 / 同一激活的重复记录不递增）。
 *       待执行动作用它做「释放发生在承诺之后」的判据（{@link PendingAction#requestedSeq}）。</li>
 * </ul>
 *
 * <p>分配契约：键用 {@link IdentityHashMap}（片段 / 绑定对象身份，不依赖 equals），条目在首次
 * 记录时创建，此后只改基本类型字段——稳态帧零分配。线程契约：仅客户端主线程。</p>
 */
public final class RuleFactStore {

    /** 片段事实：最近一次执行身份 + 该执行是否已开始 / 已完成 + 两个事实序号。 */
    private static final class ClipFacts {
        long executionId;
        boolean started;
        boolean completed;
        long startSeq;
        long completionSeq;
    }

    /** 规则事实：最近一次激活身份 + 判定成功 / 动作完成事实与序号。 */
    private static final class RuleFacts {
        long executionId;
        boolean succeeded;
        long successSeq;
        boolean actionCompleted;
        long actionExecutionId;
        long actionSeq;
    }

    private final IdentityHashMap<Clip, ClipFacts> clips = new IdentityHashMap<>();
    private final IdentityHashMap<RuleBinding, RuleFacts> rules = new IdentityHashMap<>();

    /** 全局单调事实序号（{@link #reset()} 归 0）；只随事实变化递增。 */
    private long sequence;

    /**
     * 记录片段执行已开始（执行进入时调用，时间门之前）。
     *
     * <p>{@code executionId} 变化 = 新执行：重置上一执行的 {@code completed}（事实只描述最近一次执行）。
     * 同一执行重复调用为幂等（不递增序号）。</p>
     */
    public void recordClipStarted(Clip clip, long executionId) {
        if (clip == null) return;
        ClipFacts facts = clips.get(clip);
        if (facts == null) {
            facts = new ClipFacts();
            clips.put(clip, facts);
        }
        if (facts.executionId != executionId) {
            facts.executionId = executionId;
            facts.started = true;
            facts.completed = false;
            facts.completionSeq = 0L;
            facts.startSeq = ++sequence;
        } else if (!facts.started) {
            facts.started = true;
            facts.startSeq = ++sequence;
        }
    }

    /**
     * 记录片段执行已完成（完成事实按执行身份匹配；比已记录执行更旧的身份被忽略）。
     * 同一执行的重复记录为幂等（不递增序号）。
     */
    public void recordClipCompleted(Clip clip, long executionId) {
        if (clip == null) return;
        ClipFacts facts = clips.get(clip);
        if (facts == null) {
            facts = new ClipFacts();
            clips.put(clip, facts);
        }
        if (facts.executionId > executionId) return;
        if (facts.executionId == executionId) {
            facts.started = true;
            if (!facts.completed) {
                facts.completed = true;
                facts.completionSeq = ++sequence;
            }
            return;
        }
        facts.executionId = executionId;
        facts.started = true;
        facts.startSeq = ++sequence;
        facts.completed = true;
        facts.completionSeq = ++sequence;
    }

    /**
     * 记录某绑定的判定成功（{@code rule_fact: succeeded}）。
     *
     * @param binding     绑定
     * @param executionId 该绑定所在片段的执行身份（诊断 / 后续轮次匹配用）
     */
    public void recordRuleSucceeded(RuleBinding binding, long executionId) {
        if (binding == null) return;
        RuleFacts facts = ruleFactsOf(binding);
        facts.executionId = executionId;
        facts.succeeded = true;
        facts.successSeq = ++sequence;
    }

    /**
     * 记录某绑定的动作已提交完成（{@code rule_fact: action_completed}）。
     *
     * <p>只在动作<b>实际提交之后</b>调用：判定成功与动作完成是两件事实，带 {@code schedule}
     * 的绑定在动作入队时不记录本事实。</p>
     *
     * @param binding     绑定
     * @param executionId 该绑定所在片段的执行身份（诊断 / 后续轮次匹配用）
     */
    public void recordActionCompleted(RuleBinding binding, long executionId) {
        if (binding == null) return;
        RuleFacts facts = ruleFactsOf(binding);
        facts.actionExecutionId = executionId;
        facts.actionCompleted = true;
        facts.actionSeq = ++sequence;
    }

    /** 片段最近一次已记录执行是否已开始。 */
    public boolean clipStarted(Clip clip) {
        ClipFacts facts = clip == null ? null : clips.get(clip);
        return facts != null && facts.started;
    }

    /** 片段最近一次已记录执行是否已完成。 */
    public boolean clipCompleted(Clip clip) {
        ClipFacts facts = clip == null ? null : clips.get(clip);
        return facts != null && facts.completed;
    }

    /** 绑定最近一次激活的判定是否已成功。 */
    public boolean ruleSucceeded(RuleBinding binding) {
        RuleFacts facts = binding == null ? null : rules.get(binding);
        return facts != null && facts.succeeded;
    }

    /** 绑定最近一次激活的动作是否已提交完成。 */
    public boolean actionCompleted(RuleBinding binding) {
        RuleFacts facts = binding == null ? null : rules.get(binding);
        return facts != null && facts.actionCompleted;
    }

    /**
     * 片段最近一次已记录执行身份（{@code 0} = 尚无记录）。
     * 用于「本执行是否已记录开始」与诊断。
     */
    public long clipExecutionId(Clip clip) {
        ClipFacts facts = clip == null ? null : clips.get(clip);
        return facts != null ? facts.executionId : 0L;
    }

    /** 片段最近一次开始事实的序号（{@code 0} = 尚无开始事实）。 */
    public long clipStartSeq(Clip clip) {
        ClipFacts facts = clip == null ? null : clips.get(clip);
        return facts != null ? facts.startSeq : 0L;
    }

    /** 片段最近一次完成事实的序号（{@code 0} = 尚无完成事实）。 */
    public long clipCompletionSeq(Clip clip) {
        ClipFacts facts = clip == null ? null : clips.get(clip);
        return facts != null ? facts.completionSeq : 0L;
    }

    /** 绑定最近一次成功事实的序号（{@code 0} = 尚无成功事实）。 */
    public long ruleSuccessSeq(RuleBinding binding) {
        RuleFacts facts = binding == null ? null : rules.get(binding);
        return facts != null ? facts.successSeq : 0L;
    }

    /** 绑定最近一次动作完成事实的序号（{@code 0} = 尚无动作完成事实）。 */
    public long ruleActionSeq(RuleBinding binding) {
        RuleFacts facts = binding == null ? null : rules.get(binding);
        return facts != null ? facts.actionSeq : 0L;
    }

    /** 全局事实序号（单调递增；{@link #reset()} 归 0）。 */
    public long sequence() {
        return sequence;
    }

    /**
     * 清除某绑定的激活事实：新激活建立时由 {@link RuleRuntime} 调用——事实作用域 = 最近一次激活，
     * 新激活尚未成功、也尚未提交动作。
     *
     * <p>包内可见：不是条件树可查询的事实类型，避免公共 API 误用。</p>
     */
    void clearActivationFacts(RuleBinding binding) {
        RuleFacts facts = binding == null ? null : rules.get(binding);
        if (facts == null) return;
        facts.succeeded = false;
        facts.successSeq = 0L;
        facts.actionCompleted = false;
        facts.actionSeq = 0L;
    }

    /** 清空全部事实与序号（引擎重建 / 停播）。 */
    public void reset() {
        clips.clear();
        rules.clear();
        sequence = 0L;
    }

    private RuleFacts ruleFactsOf(RuleBinding binding) {
        RuleFacts facts = rules.get(binding);
        if (facts == null) {
            facts = new RuleFacts();
            rules.put(binding, facts);
        }
        return facts;
    }

    @Override
    public String toString() {
        return String.format("RuleFactStore{clips=%d, rules=%d, seq=%d}",
                clips.size(), rules.size(), sequence);
    }
}
