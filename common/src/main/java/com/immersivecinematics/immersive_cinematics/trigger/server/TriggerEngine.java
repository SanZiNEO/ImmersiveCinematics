package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.ContinuitySpec;
import com.immersivecinematics.immersive_cinematics.script.TriggerRequirement;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.MotionSample;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.PlayerMotion;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.TriggerEdge;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.TriggerEvalContext;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.Fact;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactLedger;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactQueries;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactResult;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactType;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
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

    /** 区域边沿状态：玩家 → (scriptId + ":" + triggerId) → 状态机。键与触发器身份一致。 */
    private final Map<UUID, Map<String, TriggerEdge>> edges = new HashMap<>();

    private boolean initialized = false;

    private TriggerEngine() {}

    public void initialize() {
        initialized = true;
        PlayerMotion.INSTANCE.clearAll();
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
                int interval = reg.getEffectivePollInterval();
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
        edges.clear();
    }

    /** 玩家退出：清掉该玩家的全部区域边沿状态（触发资格 / 停留起算 / 采样点）。 */
    public void clearPlayer(UUID player) {
        edges.remove(player);
    }

    // ===== Event-driven entry =====

    /**
     * 事件驱动触发器入口：按事件类型 id（如 {@code "advancement"}、{@code "entity_kill"}）
     * 查索引并逐个求值。由 {@code ServerEventHandler} 的 Architectury 事件回调调用。
     */
    public void onGameEvent(String eventTypeId, ServerPlayer player) {
        if (!initialized) return;

        List<TriggerRegistration> triggers = eventIndex.get(eventTypeId);
        if (triggers == null || triggers.isEmpty()) return;

        int tick = player.server.getTickCount();
        for (TriggerRegistration reg : triggers) {
            try {
                if (!prerequisitesMet(player, reg)) continue;
                if (shouldSkip(player, reg)) continue;
                // 事件入口没有「离开」事件可依：只在条件为真时推进进入态，
                // 因此 on_enter 的事件类触发器仍然只触发一次（与旧的进入态布尔语义一致）。
                boolean inRegion = evaluateSafely(reg, player, reg.getConditions(),
                        TriggerEvalContext.disabled(tick));
                if (inRegion) {
                    if (reg.isOnEnter()) {
                        TriggerEdge.Decision decision = edgeFor(player, reg)
                                .observe(true, true, true, tick, 0, true);
                        if (decision.entered()) {
                            recordRegion(FactType.REGION_ENTERED, player, reg, tick);
                        }
                        if (!decision.fire()) continue;
                    }
                    fireTrigger(player, reg);
                }
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited("Trigger", "event:" + eventTypeId + ":" + reg.getTriggerId(), 5000L,
                        "触发器事件处理异常（event=" + eventTypeId + ", trigger=" + reg.getTriggerId()
                                + ", script=" + reg.getScriptId() + ", player=" + player.getName().getString()
                                + "）：该触发器本轮跳过，其他触发器继续", e);
            }
        }
    }

    // ===== Polling entry =====

    public void onServerTick(MinecraftServer server) {
        if (!initialized) return;
        // 采样基线必须先于求值推进：本 tick 的位移 / 载具变化决定代次，代次决定能否扫掠。
        PlayerMotion.INSTANCE.tick(server);
        tickCounter++;
        int tick = server.getTickCount();

        for (var entry : pollBuckets.int2ObjectEntrySet()) {
            int interval = entry.getIntKey();
            if (tickCounter % interval != 0) continue;

            for (TriggerRegistration reg : entry.getValue()) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    try {
                        if (!prerequisitesMet(player, reg)) continue;
                        if (shouldSkip(player, reg)) continue;
                        evaluatePolling(player, reg, tick);
                    } catch (Exception e) {
                        FatalErrors.rethrowIfFatal(e);
                        ErrorLog.logRateLimited("Trigger", "poll:" + reg.getTriggerId(), 5000L,
                                "触发器轮询异常（trigger=" + reg.getTriggerId() + ", script=" + reg.getScriptId()
                                        + ", player=" + player.getName().getString()
                                        + "）：该触发器本轮跳过，其他触发器继续", e);
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
                    try {
                        executeActions(player, df.reg);
                    } catch (Exception e) {
                        FatalErrors.rethrowIfFatal(e);
                        ErrorLog.logRateLimited("Trigger", "delayed:" + df.reg.getTriggerId(), 5000L,
                                "延迟触发器执行异常（trigger=" + df.reg.getTriggerId()
                                        + ", script=" + df.reg.getScriptId()
                                        + ", player=" + player.getName().getString()
                                        + "）：跳过本次动作，继续处理其他延迟触发", e);
                    }
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

    public void onScriptFinished(ServerPlayer player, String scriptId, String instanceId, CompletionReason reason) {
        // 结束事实带实例与退出原因：前置条件（script_played / script_completed）与“最近一次结束”都查它。
        FactLedger.INSTANCE.record(player.getUUID(), Fact.playbackFinished(
                scriptId, instanceId, FactResult.of(reason), player.server.getTickCount()));
        ScriptEventManager.INSTANCE.onScriptFinished(player, scriptId, instanceId, reason);
        LOGGER.debug("Script finished: player={}, script={}, instance={}, reason={}",
                player.getName().getString(), scriptId, instanceId, reason);
    }

    public void onPlaybackStarted(ServerPlayer player, String scriptId, String instanceId) {
        // “开始播放”与“结束播放”两条事实共同构成“播放过”语义。
        // 触发器状态机的键 (玩家, 脚本, 触发器) 与实例无关；播放账本（观看者 / 跳过投票 / 事件时间线）
        // 才按实例记账——所以这里两个都要喂。
        FactLedger.INSTANCE.record(player.getUUID(), Fact.playbackStarted(
                scriptId, instanceId, player.server.getTickCount()));
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
     * 触发器命中前的跳过判定。
     * “正在播放”门控是同脚本口径：只有该玩家正在播放本触发器所指向的脚本（{@code reg.getScriptId()}）
     * 时才跳过，即同脚本同玩家保持单实例；跨脚本不阻塞——播放脚本 A 期间，指向脚本 B 的触发器照常命中。
     * 随后才是 non-repeatable 触发器的“已触发过”去重。
     */
    private boolean shouldSkip(ServerPlayer player, TriggerRegistration reg) {
        if (ScriptEventManager.INSTANCE.isPlayerPlayingScript(player.getUUID(), reg.getScriptId())) {
            return true;
        }
        if (!reg.isRepeatable()) {
            return FactQueries.triggerFired(player.getUUID(), reg.getScriptId(), reg.getTriggerId());
        }
        return false;
    }

    private void fireTrigger(ServerPlayer player, TriggerRegistration reg) {
        LOGGER.info("Firing trigger '{}' for script '{}' (player: {})",
                reg.getTriggerId(), reg.getScriptId(), player.getName().getString());
        boolean existed = FactQueries.triggerFired(
                player.getUUID(), reg.getScriptId(), reg.getTriggerId());
        if (existed && !reg.isRepeatable()) return;

        FactLedger.INSTANCE.record(player.getUUID(), Fact.triggerFired(
                reg.getScriptId(), reg.getTriggerId(), player.server.getTickCount()));

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
            try {
                action.execute(player);
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited("Trigger", "action:" + reg.getTriggerId() + ":" + action.getClass().getName(),
                        5000L, "触发器动作异常（trigger=" + reg.getTriggerId() + ", script=" + reg.getScriptId()
                                + ", player=" + player.getName().getString()
                                + ", action=" + action.getClass().getSimpleName()
                                + "）：跳过该动作，继续后续动作", e);
            }
        }
    }

    private record DelayedFire(TriggerRegistration reg, int fireTick) {}

    /**
     * 轮询求值：未声明 {@code continuity} 的非 {@code on_enter} 触发器走旧快速路径（无状态、无采样），
     * 其余走边沿状态机（采样 / 代次 / 扫掠 / 停留）。
     */
    private void evaluatePolling(ServerPlayer player, TriggerRegistration reg, int tick) {
        ContinuitySpec spec = reg.getContinuity();
        if (!reg.isOnEnter() && spec.isDefault()) {
            // 旧路径：点判定，命中即触发。行为与 H1-6 之前逐位一致。
            if (evaluateSafely(reg, player, reg.getConditions(), TriggerEvalContext.disabled(tick))) {
                fireTrigger(player, reg);
            }
            return;
        }

        TriggerEdge edge = edgeFor(player, reg);
        int generation = PlayerMotion.INSTANCE.generation(player.getUUID());
        MotionSample now = PlayerMotion.INSTANCE.capture(player, tick);
        // 连续 = 有上一采样点且期间没有跳变（传送 / 维度切换 / 载具变化 / 位移超阈值）。
        boolean continuous = edge.hasSample() && edge.generation() == generation;
        TriggerEvalContext ctx = new TriggerEvalContext(tick, spec.sweep() && continuous,
                continuous ? edge.lastSample() : null, now, spec.windowTicks());

        boolean inOriginal = evaluateSafely(reg, player, reg.getConditions(), ctx);
        JsonObject exitConditions = reg.getExitConditions();
        boolean inExit = exitConditions != null
                ? evaluateSafely(reg, player, exitConditions, ctx)
                : inOriginal;

        TriggerEdge.Decision decision = edge.observe(inOriginal, inExit, continuous, tick,
                spec.dwellTicks(), reg.isOnEnter());
        // 采样点每轮都更新（即使本轮不触发）：下一次求值要用它做段判定。
        edge.setSample(now, generation);

        if (reg.isOnEnter()) {
            // 区域事实只在 on_enter 触发器上记（与非 on_enter 的旧语义一致：后者没有进入/离开概念）。
            if (decision.entered()) recordRegion(FactType.REGION_ENTERED, player, reg, tick);
            if (decision.left()) recordRegion(FactType.REGION_LEFT, player, reg, tick);
        }
        if (decision.fire()) {
            fireTrigger(player, reg);
        }
    }

    /** 取（必要时创建）该玩家在该触发器上的边沿状态机。键 = {@code scriptId + ":" + triggerId}。 */
    private TriggerEdge edgeFor(ServerPlayer player, TriggerRegistration reg) {
        return edges.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .computeIfAbsent(reg.getScriptId() + ":" + reg.getTriggerId(), k -> new TriggerEdge());
    }

    /** 记一条区域进入 / 离开事实（会话内，退出即清）。 */
    private void recordRegion(FactType type, ServerPlayer player, TriggerRegistration reg, int tick) {
        FactLedger.INSTANCE.record(player.getUUID(),
                Fact.region(type, reg.getScriptId(), reg.getTriggerId(), tick));
    }

    /**
     * 安全求值：触发器条件数据损坏/缺失时只跳过该触发器并记录错误，不拖垮服务端。
     */
    private boolean evaluateSafely(TriggerRegistration reg, ServerPlayer player) {
        return evaluateSafely(reg, player, reg.getConditions(), TriggerEvalContext.disabled(0));
    }

    private boolean evaluateSafely(TriggerRegistration reg, ServerPlayer player, JsonObject conditions) {
        return evaluateSafely(reg, player, conditions, TriggerEvalContext.disabled(0));
    }

    private boolean evaluateSafely(TriggerRegistration reg, ServerPlayer player, JsonObject conditions,
                                   TriggerEvalContext ctx) {
        try {
            return reg.getType().evaluate(player, conditions, ctx);
        } catch (Exception e) {
            LOGGER.error("Failed to evaluate trigger '{}' for script '{}' (player: {}); skipping trigger",
                    reg.getTriggerId(), reg.getScriptId(), player.getName().getString(), e);
            return false;
        }
    }
}
