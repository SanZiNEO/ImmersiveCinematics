package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.trigger.network.S2CTriggerStateSyncPacket;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Set;

public class ClientTriggerStateCache {

    private static final ClientTriggerStateCache INSTANCE = new ClientTriggerStateCache();

    private Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> triggeredScripts = new Object2ObjectOpenHashMap<>();
    private ObjectOpenHashSet<String> completedScripts = new ObjectOpenHashSet<>();

    private ClientTriggerStateCache() {}

    public static void handleSync(S2CTriggerStateSyncPacket packet) {
        INSTANCE.triggeredScripts = packet.getTriggeredScripts();
        INSTANCE.completedScripts = packet.getCompletedScripts();
    }

    public static boolean isTriggered(String scriptId, String triggerId) {
        Set<String> triggers = INSTANCE.triggeredScripts.get(scriptId);
        return triggers != null && triggers.contains(triggerId);
    }

    public static boolean isScriptCompleted(String scriptId) {
        return INSTANCE.completedScripts.contains(scriptId);
    }

    public static Set<String> getTriggeredTriggers(String scriptId) {
        return INSTANCE.triggeredScripts.get(scriptId);
    }

    public static ObjectOpenHashSet<String> getCompletedScripts() {
        return INSTANCE.completedScripts;
    }
}
