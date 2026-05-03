package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.Map;

public class TriggerDefinition {

    private final String type;
    private final Map<String, Object> conditions;
    private final boolean repeatable;
    private final float delay;

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable) {
        this(type, conditions, repeatable, 0f);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay) {
        this.type = type;
        this.conditions = conditions != null ? conditions : Collections.emptyMap();
        this.repeatable = repeatable;
        this.delay = delay;
    }

    public String getType() { return type; }
    public Map<String, Object> getConditions() { return conditions; }
    public boolean isRepeatable() { return repeatable; }
    public float getDelay() { return delay; }

    @Override
    public String toString() {
        return String.format("TriggerDefinition{type=%s, repeatable=%s, delay=%.1f, conditions=%s}",
                type, repeatable, delay, conditions);
    }
}
