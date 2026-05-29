package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
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

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, 0, false, 0f, null);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, false, 0f, null);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, delayMs, onEnter, 0f, null);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                                JsonObject conditions, List<TriggerAction> actions,
                                boolean repeatable, int delayMs, boolean onEnter, float exitBuffer, JsonObject exitConditions) {
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
}
