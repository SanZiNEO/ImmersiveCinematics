package com.immersivecinematics.immersive_cinematics.script.rule;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitPolicy;

/**
 * 规则绑定（脚本级 {@code rule_bindings[]} 的一条）：定义 × 目标片段 × 时间门 × 控制 / 调度 / 出口覆盖。
 *
 * <p>同一 {@link RuleDefinition} 可以有多条绑定（多入口），各自独立创建运行态；绑定本身不可变，
 * 运行态（激活 / 事实 / 待执行动作）由后续轮次的 {@code RuleRuntime} 持有。</p>
 *
 * <p>时间门 {@code gateTime} = 目标片段执行内部的局部秒数：目标片段有限时
 * {@code 0 <= gateTime <= duration}，无限时 {@code gateTime >= 0}（解析期 / 校验期同一口径）。</p>
 */
public final class RuleBinding {

    private final String id;
    private final RuleDefinition definition;
    private final Clip target;
    private final double gateTime;
    private final RuleControl control;
    private final RuleSchedule schedule;
    private final ClipExitPolicy exitOverride;

    /**
     * @param id           绑定 id（脚本内唯一）
     * @param definition   规则定义（不得为 {@code null}）
     * @param target       宿主片段（不得为 {@code null}）
     * @param gateTime     时间门（局部秒，{@code >= 0}）
     * @param control      执行控制（不得为 {@code null}）
     * @param schedule     动作调度（{@code null} 视为立即提交）
     * @param exitOverride 入口出口覆盖（{@code null} = 用片段默认出口）
     */
    public RuleBinding(String id, RuleDefinition definition, Clip target, double gateTime,
                       RuleControl control, RuleSchedule schedule, ClipExitPolicy exitOverride) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("绑定 id 不能为空");
        }
        if (definition == null) {
            throw new IllegalArgumentException("绑定 " + id + " 缺少规则定义");
        }
        if (target == null) {
            throw new IllegalArgumentException("绑定 " + id + " 缺少目标片段");
        }
        if (!Double.isFinite(gateTime) || gateTime < 0.0) {
            throw new IllegalArgumentException("绑定 " + id + " 的时间门必须是有限非负数: " + gateTime);
        }
        if (!target.isInfinite() && gateTime > target.getDuration()) {
            throw new IllegalArgumentException("绑定 " + id + " 的时间门越界: " + gateTime
                    + "（片段时长 " + target.getDuration() + "）");
        }
        if (control == null) {
            throw new IllegalArgumentException("绑定 " + id + " 缺少控制方式");
        }
        this.id = id;
        this.definition = definition;
        this.target = target;
        this.gateTime = gateTime;
        this.control = control;
        this.schedule = schedule == null ? RuleSchedule.IMMEDIATE : schedule;
        this.exitOverride = exitOverride;
    }

    /** 绑定 id（脚本内唯一）。 */
    public String id() {
        return id;
    }

    /** 规则定义。 */
    public RuleDefinition definition() {
        return definition;
    }

    /** 宿主片段。 */
    public Clip target() {
        return target;
    }

    /** 宿主片段 id（{@code target.scriptClipId()}）。 */
    public String clipId() {
        return target.scriptClipId();
    }

    /** 时间门（目标片段执行内部的局部秒）。 */
    public double gateTime() {
        return gateTime;
    }

    /** 执行控制（{@link RuleControl#OBSERVE} / {@link RuleControl#HOLD}）。 */
    public RuleControl control() {
        return control;
    }

    /** 动作调度（{@link RuleSchedule#isImmediate()} 为 true = 成功后立即提交）。 */
    public RuleSchedule schedule() {
        return schedule;
    }

    /** 入口出口覆盖（{@code null} = 用片段默认出口）。 */
    public ClipExitPolicy exitOverride() {
        return exitOverride;
    }

    @Override
    public String toString() {
        return String.format("RuleBinding{id=%s, rule=%s, clip=%s, gate=%s, control=%s}",
                id, definition.id(), clipId(), gateTime, control.scriptName());
    }
}
