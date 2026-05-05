package com.immersivecinematics.immersive_cinematics.editor.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class EditorTrigger {
    public String id;
    public String type;
    public Map<String, Object> conditions = new LinkedHashMap<>();
    public boolean repeatable;
    public float delay;

    public EditorTrigger() {}

    public EditorTrigger copy() {
        EditorTrigger t = new EditorTrigger();
        t.id = id;
        t.type = type;
        t.conditions.putAll(conditions);
        t.repeatable = repeatable;
        t.delay = delay;
        return t;
    }
}
