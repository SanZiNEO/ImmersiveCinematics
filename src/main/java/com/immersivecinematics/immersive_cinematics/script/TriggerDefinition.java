package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.Map;

public class TriggerDefinition {

    private final String type;
    private final Map<String, Object> conditions;
    private final boolean repeatable;
    private final float delay;
    private final boolean onEnter;

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable) {
        this(type, conditions, repeatable, 0f, false);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay) {
        this(type, conditions, repeatable, delay, false);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter) {
        this.type = type;
        this.conditions = conditions != null ? conditions : Collections.emptyMap();
        this.repeatable = repeatable;
        this.delay = delay;
        this.onEnter = onEnter;
    }

    public String getType() { return type; }
    public Map<String, Object> getConditions() { return conditions; }
    public boolean isRepeatable() { return repeatable; }
    public float getDelay() { return delay; }
    public boolean isOnEnter() { return onEnter; }

    @Override
    public String toString() {
        return String.format("TriggerDefinition{type=%s, repeatable=%s, delay=%.1f, conditions=%s}",
                type, repeatable, delay, conditions);
    }
}
