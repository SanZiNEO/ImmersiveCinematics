package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.server.action.TriggerAction;

import java.util.List;

public class TriggerRegistration {

    private final String scriptId;
    private final String triggerId;
    private final TriggerType type;
    private final JsonObject conditions;
    private final List<TriggerAction> actions;
    private final boolean repeatable;
    private final int delayMs;

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                               JsonObject conditions, List<TriggerAction> actions,
                               boolean repeatable) {
        this(scriptId, triggerId, type, conditions, actions, repeatable, 0);
    }

    public TriggerRegistration(String scriptId, String triggerId, TriggerType type,
                               JsonObject conditions, List<TriggerAction> actions,
                               boolean repeatable, int delayMs) {
        this.scriptId = scriptId;
        this.triggerId = triggerId;
        this.type = type;
        this.conditions = conditions;
        this.actions = actions;
        this.repeatable = repeatable;
        this.delayMs = delayMs;
    }

    public String getScriptId() { return scriptId; }
    public String getTriggerId() { return triggerId; }
    public TriggerType getType() { return type; }
    public JsonObject getConditions() { return conditions; }
    public List<TriggerAction> getActions() { return actions; }
    public boolean isRepeatable() { return repeatable; }
    public int getDelayMs() { return delayMs; }
}
