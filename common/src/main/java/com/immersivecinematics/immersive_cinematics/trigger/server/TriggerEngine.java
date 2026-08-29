package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TriggerRequirement;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CTriggerStateSyncPacket;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.PlayerTriggerState;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.*;

public class TriggerEngine {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TriggerEngine INSTANCE = new TriggerEngine();

    private final Map<String, List<TriggerRegistration>> eventIndex = new HashMap<>();
    private final Int2ObjectMap<List<TriggerRegistration>> pollBuckets = new Int2ObjectOpenHashMap<>();
    private final List<TriggerRegistration> allRegistrations = new ArrayList<>();
    private int tickCounter = 0;

    private final Map<UUID, List<DelayedFire>> delayedFires = new HashMap<>();

    private final Map<UUID, Map<String, Boolean>> enterStates = new HashMap<>();

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
                // 用触发器类型 ID 作为事件索引键
                eventIndex.computeIfAbsent(type.getId(), k -> new ArrayList<>()).add(reg);
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
        enterStates.clear();
    }

    // ===== Event-driven entry =====

    /**
     * 事件驱动触发器入口。
     * <p>
     * 由 {@code ServerEventHandler} 中的 Architectury 事件回调调用，
     * 传入事件类型 ID（如 {@code "advancement"}、{@code "entity_kill"}）。
     */
    public void onGameEvent(String eventTypeId, ServerPlayer player) {
        if (!initialized) return;

        List<TriggerRegistration> triggers = eventIndex.get(eventTypeId);
        if (triggers == null || triggers.isEmpty()) return;

        for (TriggerRegistration reg : triggers) {
            if (!prerequisitesMet(player, reg)) continue;
            if (shouldSkip(player, reg)) continue;
            if (evaluateSafely(reg, player)) {
                if (reg.isOnEnter() && !checkEnterState(player, reg)) continue;
                fireTrigger(player, reg);
            }
        }
    }

    // ===== Polling entry =====

    public void onServerTick(MinecraftServer server) {
        if (!initialized) return;
        tickCounter++;

        for (var entry : pollBuckets.int2ObjectEntrySet()) {
            int interval = entry.getIntKey();
            if (tickCounter % interval != 0) continue;

            for (TriggerRegistration reg : entry.getValue()) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!prerequisitesMet(player, reg)) continue;
                    if (shouldSkip(player, reg)) continue;
                    if (evaluateSafely(reg, player)) {
                        if (reg.isOnEnter() && !checkEnterState(player, reg)) continue;
                        fireTrigger(player, reg);
                    }
                }
            }
        }

        processDelayedFires(server);
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
        // 完成状态落库 + 同步到客户端（C2SScriptFinishedPacket 的必经链路）
        TriggerStateStore.INSTANCE.markScriptCompleted(player.getUUID(), scriptId);
        PlayerTriggerState state = TriggerStateStore.INSTANCE.getOrCreate(player.getUUID());
        S2CTriggerStateSyncPacket.send(player, state.getTriggeredScripts(), state.getCompletedScripts());
        ScriptEventManager.INSTANCE.onScriptFinished(player, scriptId, reason);
        LOGGER.debug("Script finished: player={}, script={}, reason={}",
                player.getName().getString(), scriptId, reason);
    }

    public void onPlaybackStarted(ServerPlayer player, String scriptId) {
        // 记录“开始播放”信号：配合结束信号构成“播放过”语义
        TriggerStateStore.INSTANCE.markScriptStarted(player.getUUID(), scriptId);
        ScriptEventManager.INSTANCE.startPlayback(player, scriptId);
    }

    // ===== Internal =====

    /**
     * 前置条件检查：requires 中任一条件不满足 → 跳过（AND 语义）。
     * 内置 script_played 要求“开始播放 && 结束播放（任何退出原因）”，
     * 其他类型由自定义注册者定义。放在 shouldSkip 之前——依赖未解锁时连去重逻辑都不需要碰。
     */
    private boolean prerequisitesMet(ServerPlayer player, TriggerRegistration reg) {
        List<TriggerRequirement> requires = reg.getRequires();
        if (requires.isEmpty()) return true;
        for (TriggerRequirement req : requires) {
            if (!PrerequisiteRegistry.evaluate(req.getType(), player, req.getData())) {
                return false;
            }
        }
        return true;
    }

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

        // 触发成功 → 同步最新状态到客户端（编辑器 UI 消费）
        PlayerTriggerState state = TriggerStateStore.INSTANCE.getOrCreate(player.getUUID());
        S2CTriggerStateSyncPacket.send(player, state.getTriggeredScripts(), state.getCompletedScripts());

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

    private boolean checkEnterState(ServerPlayer player, TriggerRegistration reg) {
        UUID uuid = player.getUUID();
        String key = reg.getScriptId() + ":" + reg.getTriggerId();
        Map<String, Boolean> playerStates = enterStates.computeIfAbsent(uuid, k -> new HashMap<>());
        boolean wasInside = playerStates.getOrDefault(key, false);

        JsonObject exitCond = reg.getExitConditions();
        if (exitCond != null) {
            boolean inExpanded = evaluateSafely(reg, player, exitCond);
            boolean inOriginal = evaluateSafely(reg, player);

            if (inOriginal) {
                playerStates.put(key, true);
                return !wasInside;
            }
            if (!inExpanded) {
                playerStates.put(key, false);
            }
            return false;
        }

        boolean isInside = evaluateSafely(reg, player);
        playerStates.put(key, isInside);
        return isInside && !wasInside;
    }

    /**
     * 安全求值：触发器条件数据损坏/缺失时只跳过该触发器并记录错误，不拖垮服务端。
     */
    private boolean evaluateSafely(TriggerRegistration reg, ServerPlayer player) {
        return evaluateSafely(reg, player, reg.getConditions());
    }

    private boolean evaluateSafely(TriggerRegistration reg, ServerPlayer player, JsonObject conditions) {
        try {
            return reg.getType().evaluate(player, conditions);
        } catch (Exception e) {
            LOGGER.error("Failed to evaluate trigger '{}' for script '{}' (player: {}); skipping trigger",
                    reg.getTriggerId(), reg.getScriptId(), player.getName().getString(), e);
            return false;
        }
    }
}
