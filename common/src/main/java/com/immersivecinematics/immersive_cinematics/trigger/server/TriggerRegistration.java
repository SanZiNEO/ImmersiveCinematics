package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TriggerRequirement;
import com.immersivecinematics.immersive_cinematics.trigger.server.action.TriggerAction;

import java.util.List;

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
    /** 生效轮询间隔（tick）：触发器级 ?? 脚本级 ?? 类型级解析结果（≥ 1，见 {@link TriggerType#resolvePollInterval}）；
     *  事件驱动类型为 0（不参与轮询，不入桶） */
    private final int effectivePollInterval;

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, 0, false, 0f, null, null);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, false, 0f, null, null);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, 0f, null, null);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter, float exitBuffer, JsonObject exitConditions) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, exitBuffer, exitConditions, null);
    }

    /** 旧重载：生效间隔缺省 = 类型级默认（{@link TriggerType#getPollInterval()}） */
    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter, float exitBuffer, JsonObject exitConditions,
                                List<TriggerRequirement> requires) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, exitBuffer, exitConditions,
                requires, type.getPollInterval());
    }

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

    public String getScriptId() { return scriptId; }
    public String getTriggerId() { return triggerId; }
    public TriggerType getType() { return type; }
    public JsonObject getConditions() { return conditions; }
    public JsonObject getExitConditions() { return exitConditions; }
    public List<TriggerAction> getActions() { return actions; }
    public boolean isRepeatable() { return repeatable; }
    public int getDelayMs() { return delayMs; }
    public boolean isOnEnter() { return onEnter; }
    public float getExitBuffer() { return exitBuffer; }
    public List<TriggerRequirement> getRequires() { return requires; }
    /** 生效轮询间隔（tick）：轮询类 ≥ 1；事件驱动 = 0（不入桶） */
    public int getEffectivePollInterval() { return effectivePollInterval; }
}
