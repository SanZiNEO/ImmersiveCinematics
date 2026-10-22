package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TriggerRequirement;
import com.immersivecinematics.immersive_cinematics.trigger.server.action.TriggerAction;

import java.util.List;

/**
 * 一条已注册的触发器：脚本 + 触发器 id + 类型 + 条件 + 动作，是 {@code TriggerEngine} 索引与调度的单位。
 * 不可变快照：脚本重载时整体重建，运行期只读。
 */
public class TriggerRegistration {

    private final String scriptId;
    private final String triggerId;
    private final TriggerType type;
    private final JsonObject conditions;
    private final JsonObject exitConditions;
    private final List<TriggerAction> actions;
    private final boolean repeatable;
    private final int delayMs;
    private final boolean onEnter;
    private final float exitBuffer;
    /** 前置条件：AND 语义，全部满足才允许触发；空 = 无前置 */
    private final List<TriggerRequirement> requires;
    /** 生效轮询间隔（tick）：轮询类 ≥ 1，事件驱动类 = 0（不入桶） */
    private final int effectivePollInterval;

    /** 最简构造：无延迟、非 on_enter、无外扩、无退出条件、无前置。 */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, 0, false, 0f, null, null);
    }

    /** 增加命中后的延迟（毫秒）。 */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, false, 0f, null, null);
    }

    /** 增加 on_enter 语义（只在进入瞬间触发）。 */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, 0f, null, null);
    }

    /** 增加离开外扩与退出条件（离开复位判定用）。 */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter, float exitBuffer, JsonObject exitConditions) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, exitBuffer, exitConditions, null);
    }

    /** 增加前置条件；生效间隔缺省 = 类型级默认（{@link TriggerType#getPollInterval()}）。 */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter, float exitBuffer, JsonObject exitConditions,
                                List<TriggerRequirement> requires) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, exitBuffer, exitConditions,
                requires, type.getPollInterval());
    }

    /** 全参构造：{@code effectivePollInterval} 由脚本级 / 触发器级覆盖解析后传入。 */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter, float exitBuffer, JsonObject exitConditions,
                                List<TriggerRequirement> requires, int effectivePollInterval) {
        this.scriptId = scriptId;
        this.triggerId = triggerId;
        this.type = type;
        this.conditions = conditions;
        this.exitConditions = exitConditions;
        this.actions = actions;
        this.repeatable = repeatable;
        this.delayMs = delayMs;
        this.onEnter = onEnter;
        this.exitBuffer = exitBuffer;
        this.requires = requires != null ? requires : List.of();
        this.effectivePollInterval = effectivePollInterval;
    }

    /** 触发器所属脚本 id。 */
    public String getScriptId() { return scriptId; }
    /** 脚本内唯一的触发器 id（non-repeatable 去重键的一半）。 */
    public String getTriggerId() { return triggerId; }
    /** 触发器类型：提供求值器与监听策略。 */
    public TriggerType getType() { return type; }
    /** 触发条件 JSON，原样交给类型求值器。 */
    public JsonObject getConditions() { return conditions; }
    /** 离开复位条件；{@code null} = 直接用 inOriginal 布尔复位。 */
    public JsonObject getExitConditions() { return exitConditions; }
    /** 命中后依次执行的动作。 */
    public List<TriggerAction> getActions() { return actions; }
    /** 是否可重复触发；{@code false} 时按 (脚本, 触发器) 去重。 */
    public boolean isRepeatable() { return repeatable; }
    /** 命中后延迟执行动作的毫秒数；0 = 立即。 */
    public int getDelayMs() { return delayMs; }
    /** 是否只在“进入”瞬间触发（离开需复位后才可再次触发）。 */
    public boolean isOnEnter() { return onEnter; }
    /** 离开判定的区域外扩格数；0 = 不外扩。 */
    public float getExitBuffer() { return exitBuffer; }
    /** 前置条件（AND）；空 = 无前置。 */
    public List<TriggerRequirement> getRequires() { return requires; }
    /** 生效轮询间隔（tick）：轮询类 ≥ 1；事件驱动 = 0（不入桶） */
    public int getEffectivePollInterval() { return effectivePollInterval; }
}
