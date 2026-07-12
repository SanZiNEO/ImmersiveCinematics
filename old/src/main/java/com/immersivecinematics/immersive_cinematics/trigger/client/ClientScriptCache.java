package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;

import java.util.HashMap;
import java.util.Map;

public class ClientScriptCache {

    private static final Map<String, CinematicScript> CACHE = new HashMap<>();

    public static void putScript(String id, CinematicScript script) {
        CACHE.put(id, script);
    }

    public static CinematicScript getScript(String id) {
        return CACHE.get(id);
    }

    public static void putAll(Map<String, CinematicScript> scripts) {
        CACHE.putAll(scripts);
    }

    public static void clear() {
        CACHE.clear();
    }

    public static int size() { return CACHE.size(); }
}
