package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TriggerDefinition {

    private final String type;
    private final Map<String, Object> conditions;
    private final boolean repeatable;
    private final float delay;
    private final boolean onEnter;
    private final float exitBuffer;
    /** 前置依赖：本触发器解锁前必须已触发的脚本 id 列表（AND 语义），空 = 无前置 */
    private final List<String> requires;

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable) {
        this(type, conditions, repeatable, 0f, false, 0f, Collections.emptyList());
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay) {
        this(type, conditions, repeatable, delay, false, 0f, Collections.emptyList());
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter) {
        this(type, conditions, repeatable, delay, onEnter, 0f, Collections.emptyList());
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter, float exitBuffer) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, Collections.emptyList());
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter, float exitBuffer, List<String> requires) {
        this.type = type;
        this.conditions = conditions != null ? conditions : Collections.emptyMap();
        this.repeatable = repeatable;
        this.delay = delay;
        this.onEnter = onEnter;
        this.exitBuffer = exitBuffer;
        this.requires = requires != null ? requires : Collections.emptyList();
    }

    public String getType() { return type; }
    public Map<String, Object> getConditions() { return conditions; }
    public boolean isRepeatable() { return repeatable; }
    public float getDelay() { return delay; }
    public boolean isOnEnter() { return onEnter; }
    public float getExitBuffer() { return exitBuffer; }
    public List<String> getRequires() { return requires; }

    @Override
    public String toString() {
        return String.format("TriggerDefinition{type=%s, repeatable=%s, delay=%.1f, requires=%s, conditions=%s}",
                type, repeatable, delay, requires, conditions);
    }
}
