package com.immersivecinematics.immersive_cinematics.trigger.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class TriggerRegistry {

    private static final Map<String, TriggerType> TYPES = new HashMap<>();

    public static void register(TriggerType type) {
        TYPES.put(type.getId(), type);
    }

    public static TriggerType get(String typeId) {
        return TYPES.get(typeId);
    }

    public static Collection<TriggerType> getAll() {
        return TYPES.values();
    }

    public static void clear() {
        TYPES.clear();
    }
}
