package com.immersivecinematics.immersive_cinematics.trigger.server.store;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Set;

public class PlayerTriggerState {

    private final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> triggeredScripts;
    private final ObjectOpenHashSet<String> completedScripts;
    private boolean dirty;

    public PlayerTriggerState() {
        this.triggeredScripts = new Object2ObjectOpenHashMap<>();
        this.completedScripts = new ObjectOpenHashSet<>();
        this.dirty = false;
    }

    public boolean isTriggered(String scriptId, String triggerId) {
        Set<String> triggers = triggeredScripts.get(scriptId);
        return triggers != null && triggers.contains(triggerId);
    }

    public boolean isScriptCompleted(String scriptId) {
        return completedScripts.contains(scriptId);
    }

    public Set<String> getTriggeredIds(String scriptId) {
        return triggeredScripts.get(scriptId);
    }

    public boolean markTriggered(String scriptId, String triggerId) {
        if (isTriggered(scriptId, triggerId)) return false;
        triggeredScripts.computeIfAbsent(scriptId, k -> new ObjectOpenHashSet<>()).add(triggerId);
        dirty = true;
        return true;
    }

    public boolean markScriptCompleted(String scriptId) {
        if (completedScripts.contains(scriptId)) return false;
        completedScripts.add(scriptId);
        dirty = true;
        return true;
    }

    public void resetScript(String scriptId) {
        triggeredScripts.remove(scriptId);
        completedScripts.remove(scriptId);
        dirty = true;
    }

    public void resetAll() {
        triggeredScripts.clear();
        completedScripts.clear();
        dirty = true;
    }

    public boolean isDirty() { return dirty; }
    public void markClean() { dirty = false; }

    public Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> getTriggeredScripts() {
        return triggeredScripts;
    }

    public ObjectOpenHashSet<String> getCompletedScripts() {
        return completedScripts;
    }
}
