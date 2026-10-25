package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CTriggerStateSyncPacket;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.PlayerTriggerState;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.advancements.Advancement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 服务端事件处理：把平台事件翻译成触发引擎 / 状态存储的调用。
 *
 * 只保留纯逻辑；平台（Fabric/Forge）负责把原生事件转发到这里。
 */
public final class ServerEventHandler {

    private ServerEventHandler() {}

    /**
     * 服务端启动：初始化顺序不可交换。
     *
     * 先 loadAll 才有脚本可供引用；再 initialize 状态存储与引擎；
     * 最后 registerAllTriggers 才能把已加载脚本的触发器挂到引擎上。
     */
    public static void onServerStarted(MinecraftServer server) {
        ScriptManager.INSTANCE.loadAll(server);
        TriggerStateStore.INSTANCE.initialize(server);
        TriggerEngine.INSTANCE.initialize();
        ScriptManager.INSTANCE.registerAllTriggers();
    }

    /** 服务端停止：把全部玩家触发状态落盘（关服前最后一次持久化）。 */
    public static void onServerStopping(MinecraftServer server) {
        TriggerStateStore.INSTANCE.saveAll();
    }

    /** 玩家加入：清空残留音频监听状态，载入并同步触发状态，再补发 login 事件。 */
    public static void onPlayerJoin(ServerPlayer serverPlayer) {
        com.immersivecinematics.immersive_cinematics.trigger.server.AudioListenerServerState.clear(serverPlayer.getUUID());
        TriggerStateStore.INSTANCE.loadForPlayer(serverPlayer.getUUID());
        PlayerTriggerState joinState = TriggerStateStore.INSTANCE.getOrCreate(serverPlayer.getUUID());
        S2CTriggerStateSyncPacket.send(
                serverPlayer, joinState.getTriggeredScripts(), joinState.getCompletedScripts());
        TriggerEngine.INSTANCE.onGameEvent("login", serverPlayer);
    }

    /** 玩家退出：清理该 UUID 的预载 / 音频 / 触发状态与各评估器快照，避免重连后读到上一轮状态。 */
    public static void onPlayerQuit(ServerPlayer serverPlayer) {
        UUID uuid = serverPlayer.getUUID();
        com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager.INSTANCE.onDisconnect(uuid, serverPlayer);
        com.immersivecinematics.immersive_cinematics.trigger.server.AudioListenerServerState.clear(uuid);
        TriggerStateStore.INSTANCE.unloadForPlayer(uuid);
        Evaluators.KillTracker.clear(uuid);
        Evaluators.DeathTracker.clear(uuid);
        Evaluators.AdvancementTracker.clear(uuid);
        Evaluators.InteractTracker.clear(uuid);
        Evaluators.CraftTracker.clear(uuid);
        Evaluators.UseItemTracker.clear(uuid);
        Evaluators.PickupDropTracker.clear(uuid);
        Evaluators.InventoryTracker.clear(uuid);
        Evaluators.DimensionTracker.clear(uuid);
    }

    /** 每 tick 驱动触发引擎、脚本事件队列与相机锚点。 */
    public static void onServerTick(MinecraftServer server) {
        try {
            TriggerEngine.INSTANCE.onServerTick(server);
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Server", "tick:trigger-engine", 5000L,
                    "服务端 tick 触发器引擎异常（本 tick 跳过该子系统，其余子系统继续）", e);
        }
        try {
            ScriptEventManager.INSTANCE.onServerTick(server);
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Server", "tick:script-event", 5000L,
                    "服务端 tick 脚本事件时间线异常（本 tick 跳过该子系统，其余子系统继续）", e);
        }
        try {
            com.immersivecinematics.immersive_cinematics.trigger.server.CameraAnchorManager.INSTANCE.tick();
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Server", "tick:camera-anchor", 5000L,
                    "服务端 tick 相机锚点异常（本 tick 跳过该子系统，其余子系统继续）", e);
        }
    }

    /** 命令注册：转交 {@code CinematicCommand}，命令树只在服务端构建一次。 */
    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.immersivecinematics.immersive_cinematics.command.CinematicCommand.register(dispatcher);
    }

    /** 成就达成：记入成就评估器后派发 advancement 事件。 */
    public static void onPlayerAdvancement(ServerPlayer player, Advancement advancement) {
        Evaluators.AdvancementTracker.record(player, advancement.getId().toString());
        TriggerEngine.INSTANCE.onGameEvent("advancement", player);
    }

    /** 实体死亡：击杀者记 entity_kill、死者记 death；两条分支独立判定（PvP 双方都触发）。 */
    public static void onLivingDeath(LivingEntity entity, DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            Evaluators.KillTracker.record(player, entity.getType(),
                    (ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ());
            TriggerEngine.INSTANCE.onGameEvent("entity_kill", player);
        }
        // PvP 双触发：击杀者走上面的 entity_kill、死者走下面的 death，两条分支独立（不是 else-if）
        if (entity instanceof ServerPlayer victim) {
            ServerLevel level = victim.serverLevel();
            Evaluators.DeathTracker.record(victim.getUUID(), level.dimension().location().toString(),
                    level.getBiome(victim.blockPosition()).unwrapKey()
                            .map(key -> key.location().toString()).orElse(""),
                    victim.getX(), victim.getY(), victim.getZ());
            TriggerEngine.INSTANCE.onGameEvent("death", victim);
        }
    }

    /** 右键方块：记方块与手持物，再派发 block_interact / item_on_interact。 */
    public static void onRightClickBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Evaluators.InteractTracker.recordBlock(serverPlayer.getUUID(),
                serverPlayer.level().getBlockState(pos));
        Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
    }

    /** 左键方块：与右键同口径记账，但只派发 block_interact（左键不构成 item_on_interact）。 */
    public static void onLeftClickBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Evaluators.InteractTracker.recordBlock(serverPlayer.getUUID(),
                serverPlayer.level().getBlockState(pos));
        Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
    }

    /** 右键实体：记实体与手持物，再派发 entity_interact / item_on_interact。 */
    public static void onInteractEntity(Player player, Entity entity, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Evaluators.InteractTracker.recordEntity(serverPlayer.getUUID(), entity.getType());
        Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("entity_interact", serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
    }

    /** 合成物品：记入合成评估器后派发 item_craft。 */
    public static void onCraftItem(ServerPlayer serverPlayer, ItemStack stack) {
        Evaluators.CraftTracker.record(serverPlayer, stack);
        TriggerEngine.INSTANCE.onGameEvent("item_craft", serverPlayer);
    }

    /** 右键物品（未命中方块）：记入使用评估器后派发 item_use。 */
    public static void onRightClickItem(ServerPlayer serverPlayer, InteractionHand hand) {
        Evaluators.UseItemTracker.recordUsed(serverPlayer, serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("item_use", serverPlayer);
    }

    /** 维度切换：记录原维度后派发 dimension_change。 */
    public static void onChangeDimension(ServerPlayer serverPlayer, ResourceKey<Level> oldLevel, ResourceKey<Level> newLevel) {
        Evaluators.DimensionTracker.record(serverPlayer.getUUID(),
                oldLevel.location().toString());
        TriggerEngine.INSTANCE.onGameEvent("dimension_change", serverPlayer);
    }

    /** 拾取物品：记入拾取/丢弃评估器后派发 item_pickup。 */
    public static void onPickupItem(ServerPlayer sp, ItemStack stack) {
        Evaluators.PickupDropTracker.recordPickup(sp, stack);
        TriggerEngine.INSTANCE.onGameEvent("item_pickup", sp);
    }

    /** 丢弃物品：记入拾取/丢弃评估器后派发 item_drop。 */
    public static void onDropItem(ServerPlayer sp, ItemStack stack) {
        Evaluators.PickupDropTracker.recordDrop(sp, stack);
        TriggerEngine.INSTANCE.onGameEvent("item_drop", sp);
    }

    /**
     * 实体加入世界：玩家投出的投掷物按「瞬时使用」记账并派发 item_instant_use。
     * 投掷物不产生右键使用事件，只能在这里补记。
     */
    public static void onEntityAdded(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile proj
                && proj.getOwner() instanceof ServerPlayer sp) {
            Evaluators.UseItemTracker.recordInstantUse(sp, proj.getItem());
            TriggerEngine.INSTANCE.onGameEvent("item_instant_use", sp);
        }
    }

    /** 世界保存：触发状态跟随存档节奏一并落盘（不只依赖关服时的持久化）。 */
    public static void onLevelSave() {
        TriggerStateStore.INSTANCE.saveAll();
    }
}
