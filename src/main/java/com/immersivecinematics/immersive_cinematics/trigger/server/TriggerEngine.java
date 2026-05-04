package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;
import org.slf4j.Logger;

import java.util.*;

public class TriggerEngine {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TriggerEngine INSTANCE = new TriggerEngine();

    private final Map<Class<? extends Event>, List<TriggerRegistration>> eventIndex = new HashMap<>();
    private final Int2ObjectMap<List<TriggerRegistration>> pollBuckets = new Int2ObjectOpenHashMap<>();
    private final List<TriggerRegistration> allRegistrations = new ArrayList<>();
    private int tickCounter = 0;

    private final Map<UUID, List<DelayedFire>> delayedFires = new HashMap<>();

    private boolean initialized = false;

    private TriggerEngine() {}

    public void initialize() {
        initialized = true;
        LOGGER.info("TriggerEngine initialized");
    }

    // ===== Registration =====

    public void registerAll(List<TriggerRegistration> registrations) {
        allRegistrations.addAll(registrations);
        rebuildIndex();
    }

    public void rebuildIndex() {
        eventIndex.clear();
        pollBuckets.clear();

        for (TriggerRegistration reg : allRegistrations) {
            TriggerType type = reg.getType();
            if (type.getStrategy() == ListenStrategy.EVENT_DRIVEN) {
                for (Class<? extends Event> eventClass : type.getListenedEvents()) {
                    eventIndex.computeIfAbsent(eventClass, k -> new ArrayList<>()).add(reg);
                }
            } else if (type.getStrategy() == ListenStrategy.POLLING) {
                int interval = type.getPollInterval();
                pollBuckets.computeIfAbsent(interval, k -> new ArrayList<>()).add(reg);
            }
        }

        LOGGER.info("Rebuilt trigger index: {} event-driven, {} polling buckets ({} total registrations)",
                eventIndex.size(), pollBuckets.size(), allRegistrations.size());
    }

    public void clear() {
        allRegistrations.clear();
        eventIndex.clear();
        pollBuckets.clear();
        delayedFires.clear();
    }

    // ===== Event-driven entry =====

    @SuppressWarnings("unchecked")
    public <T extends Event> void onGameEvent(T event, ServerPlayer player) {
        if (!initialized) return;

        List<TriggerRegistration> triggers = eventIndex.get(event.getClass());
        if (triggers == null || triggers.isEmpty()) {
            triggers = findSuperclassMatch(event.getClass());
            if (triggers == null || triggers.isEmpty()) return;
        }

        for (TriggerRegistration reg : triggers) {
            if (shouldSkip(player, reg)) continue;
            if (reg.getType().evaluate(player, reg.getConditions())) {
                fireTrigger(player, reg);
            }
        }
    }

    private <T extends Event> List<TriggerRegistration> findSuperclassMatch(Class<T> eventClass) {
        for (Map.Entry<Class<? extends Event>, List<TriggerRegistration>> entry : eventIndex.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventClass)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ===== Polling entry =====

    public void onServerTick(MinecraftServer server) {
        if (!initialized) return;

        tickCounter++;

        // 1. 处理延迟队列
        if (!delayedFires.isEmpty()) {
            processDelayedFires(server);
        }

        // 2. 处理轮询桶
        if (pollBuckets.isEmpty()) return;
        for (Int2ObjectMap.Entry<List<TriggerRegistration>> entry : pollBuckets.int2ObjectEntrySet()) {
            int interval = entry.getIntKey();
            if (tickCounter % interval != 0) continue;

            for (TriggerRegistration reg : entry.getValue()) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (shouldSkip(player, reg)) continue;
                    if (reg.getType().evaluate(player, reg.getConditions())) {
                        fireTrigger(player, reg);
                    }
                }
            }
        }
    }

    // ===== Delayed fire =====

    private void processDelayedFires(MinecraftServer server) {
        int currentTick = server.getTickCount();
        var iter = delayedFires.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            UUID playerId = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                iter.remove();
                continue;
            }

            var fires = entry.getValue();
            fires.removeIf(df -> {
                if (currentTick >= df.fireTick) {
                    executeActions(player, df.reg);
                    return true;
                }
                return false;
            });

            if (fires.isEmpty()) {
                iter.remove();
            }
        }
    }

    // ===== Script completion callback =====

    public void onScriptFinished(ServerPlayer player, String scriptId,
                                 com.immersivecinematics.immersive_cinematics.control.CompletionReason reason) {
        ScriptEventManager.INSTANCE.onScriptFinished(player, scriptId, reason);
        LOGGER.debug("Script finished: player={}, script={}, reason={}",
                player.getName().getString(), scriptId, reason);
    }

    public void onPlaybackStarted(ServerPlayer player, String scriptId) {
        ScriptEventManager.INSTANCE.startPlayback(player, scriptId);
    }

    // ===== Internal =====

    private boolean shouldSkip(ServerPlayer player, TriggerRegistration reg) {
        if (ScriptEventManager.INSTANCE.isPlayerPlayingScript(player.getUUID(), reg.getScriptId())) {
            return true;
        }
        if (!reg.isRepeatable()) {
            return TriggerStateStore.INSTANCE.isTriggered(
                    player.getUUID(), reg.getScriptId(), reg.getTriggerId());
        }
        return false;
    }

    private void fireTrigger(ServerPlayer player, TriggerRegistration reg) {
        LOGGER.info("Firing trigger '{}' for script '{}' (player: {})",
                reg.getTriggerId(), reg.getScriptId(), player.getName().getString());
        boolean isNew = TriggerStateStore.INSTANCE.markTriggered(
                player.getUUID(), reg.getScriptId(), reg.getTriggerId());
        if (!isNew && !reg.isRepeatable()) return;

        int delayMs = reg.getDelayMs();
        if (delayMs > 0) {
            int delayTicks = Math.max(1, delayMs / 50);
            delayedFires.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
                    .add(new DelayedFire(reg, player.server.getTickCount() + delayTicks));
            LOGGER.info("  delayed by {} ticks ({}ms)", delayTicks, delayMs);
            return;
        }

        executeActions(player, reg);
    }

    private void executeActions(ServerPlayer player, TriggerRegistration reg) {
        for (var action : reg.getActions()) {
            action.execute(player);
        }
    }

    private record DelayedFire(TriggerRegistration reg, int fireTick) {}
}
