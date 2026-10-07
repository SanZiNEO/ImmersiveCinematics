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
            boolean inRegion = evaluateSafely(reg, player);
            if (inRegion) {
                if (reg.isOnEnter() && !checkEnterState(player, reg, inRegion)) continue;
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
                    boolean inRegion = evaluateSafely(reg, player);
                    if (reg.isOnEnter()) {
                        // 状态机每次轮询都要更新（含“区域外复位”），否则 on_enter 只会触发一次
                        if (!checkEnterState(player, reg, inRegion)) continue;
                        if (!inRegion) continue;
                    } else if (!inRegion) {
                        continue;
                    }
                    fireTrigger(player, reg);
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

    public void onScriptFinished(ServerPlayer player, String scriptId, String instanceId,
                                 com.immersivecinematics.immersive_cinematics.control.CompletionReason reason) {
        // 完成状态落库 + 同步到客户端（C2SScriptFinishedPacket 的必经链路）
        TriggerStateStore.INSTANCE.markScriptCompleted(player.getUUID(), scriptId);
        PlayerTriggerState state = TriggerStateStore.INSTANCE.getOrCreate(player.getUUID());
        S2CTriggerStateSyncPacket.send(player, state.getTriggeredScripts(), state.getCompletedScripts());
        ScriptEventManager.INSTANCE.onScriptFinished(player, scriptId, instanceId, reason);
        LOGGER.debug("Script finished: player={}, script={}, instance={}, reason={}",
                player.getName().getString(), scriptId, instanceId, reason);
    }

    public void onPlaybackStarted(ServerPlayer player, String scriptId, String instanceId) {
        // 记录“开始播放”信号：配合结束信号构成“播放过”语义。
        // 触发器状态机的键 (玩家, 脚本, 触发器) 不变（同脚本同玩家单实例，§3.5）；
        // 播放账本（观看者 / 跳过投票 / 事件时间线）按实例记账（§3.7）。
        TriggerStateStore.INSTANCE.markScriptStarted(player.getUUID(), scriptId);
        ScriptEventManager.INSTANCE.startPlayback(player, scriptId, instanceId);
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

    /**
     * 触发器命中前的跳过判定（§3.5 重述后语义）。
     * <p>
     * “正在播放”门控是<b>同脚本</b>口径：只有该玩家正在播放<b>本触发器所指向的脚本</b>
     * （{@code reg.getScriptId()}）时才跳过，即同脚本同玩家保持单实例；
     * <b>跨脚本不阻塞</b>——播放脚本 A 期间，指向脚本 B 的触发器照常命中并新建实例
     * （客户端多实例已就绪，§3.5/§3.6）。
     * <p>
     * 随后才是 non-repeatable 触发器的“已触发过”去重。
     */
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
            List<DelayedFire> pending = delayedFires.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
            // 同一触发器的延迟触发已在队列中 → 不重复排队：
            // repeatable + delay 时玩家停留在/走过区域内会每轮（0.1~1 秒）入队一次，
            // 到点后一次性全部执行，导致脚本播完被排队连播多次（传送类脚本表现为连续传送）。
            for (DelayedFire df : pending) {
                if (df.reg() == reg) return;
            }
            pending.add(new DelayedFire(reg, player.server.getTickCount() + delayTicks));
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

    /**
     * “进入触发 + 离开复位”状态机。
     * <p>
     * 调用方每次轮询都必须调用（即使玩家在区域外），否则“离开复位”分支不可达、
     * 触发器每局只能触发一次。详见 plans/0.3.6/feedback-0.3.5/05-on-enter-not-repeatable.md。
     *
     * @param inOriginal 玩家当前是否在原始触发区域内（由调用方求值，避免重复求值）
     * @return 是否应视为“新进入”而触发
     */
    private boolean checkEnterState(ServerPlayer player, TriggerRegistration reg, boolean inOriginal) {
        UUID uuid = player.getUUID();
        String key = reg.getScriptId() + ":" + reg.getTriggerId();
        Map<String, Boolean> playerStates = enterStates.computeIfAbsent(uuid, k -> new HashMap<>());
        boolean wasInside = playerStates.getOrDefault(key, false);

        JsonObject exitCond = reg.getExitConditions();
        if (exitCond != null) {
            if (inOriginal) {
                playerStates.put(key, true);
                return !wasInside;
            }
            // 已在区域内且仍在离开缓冲（外扩区域）内：保持“已进入”状态，等待完全离开后复位
            if (!evaluateSafely(reg, player, exitCond)) {
                playerStates.put(key, false);
            }
            return false;
        }

        playerStates.put(key, inOriginal);
        return inOriginal && !wasInside;
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
