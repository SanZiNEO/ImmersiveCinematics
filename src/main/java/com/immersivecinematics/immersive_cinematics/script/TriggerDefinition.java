package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.Map;

/**
 * 触发器定义 — 嵌入在脚本 meta 中，声明脚本的触发条件
 * <p>
 * 当前仅解析存储，不参与运行时逻辑。由 TriggerEngine 消费。
 */
public class TriggerDefinition {

    private final String type;
    private final Map<String, Object> conditions;
    private final boolean repeatable;

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable) {
        this.type = type;
        this.conditions = conditions != null ? conditions : Collections.emptyMap();
        this.repeatable = repeatable;
    }

    public String getType() { return type; }
    public Map<String, Object> getConditions() { return conditions; }
    public boolean isRepeatable() { return repeatable; }

    @Override
    public String toString() {
        return String.format("TriggerDefinition{type=%s, repeatable=%s, conditions=%s}",
                type, repeatable, conditions);
    }
}
