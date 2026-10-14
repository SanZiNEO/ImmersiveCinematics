package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;

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
    /** 前置条件：AND 语义，全部满足才允许触发；空 = 无前置 */
    private final List<TriggerRequirement> requires;
    /** 触发器级轮询间隔（tick，{@link TriggerType#POLL_INTERVAL_MIN}~{@link TriggerType#POLL_INTERVAL_MAX}）；
     *  null = 未指定，运行时回落到脚本级 meta.trigger_poll_interval → 类型级默认；仅轮询类类型有效 */
    private final Integer pollInterval;

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable) {
        this(type, conditions, repeatable, 0f, false, 0f, Collections.emptyList(), null);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay) {
        this(type, conditions, repeatable, delay, false, 0f, Collections.emptyList(), null);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter) {
        this(type, conditions, repeatable, delay, onEnter, 0f, Collections.emptyList(), null);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter, float exitBuffer) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, Collections.emptyList(), null);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter, float exitBuffer, List<TriggerRequirement> requires) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, requires, null);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter,
                             float exitBuffer, List<TriggerRequirement> requires, Integer pollInterval) {
        this.type = type;
        this.conditions = conditions != null ? conditions : Collections.emptyMap();
        this.repeatable = repeatable;
        this.delay = delay;
        this.onEnter = onEnter;
        this.exitBuffer = exitBuffer;
        this.requires = requires != null ? requires : Collections.emptyList();
        this.pollInterval = pollInterval;
    }

    public String getType() { return type; }
    public Map<String, Object> getConditions() { return conditions; }
    public boolean isRepeatable() { return repeatable; }
    public float getDelay() { return delay; }
    public boolean isOnEnter() { return onEnter; }
    public float getExitBuffer() { return exitBuffer; }
    public List<TriggerRequirement> getRequires() { return requires; }
    /** 触发器级轮询间隔（tick）；null = 未指定（回落到脚本级 → 类型级默认） */
    public Integer getPollInterval() { return pollInterval; }

    @Override
    public String toString() {
        return String.format("TriggerDefinition{type=%s, repeatable=%s, delay=%.1f, requires=%s, conditions=%s}",
                type, repeatable, delay, requires, conditions);
    }
}
