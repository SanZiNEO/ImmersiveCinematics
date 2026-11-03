package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraViewManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.PlayerMotion;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.*;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.advancements.Advancement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * 服务端事件处理：把平台事件翻译成事实记账 / 触发引擎的调用。
 *
 * 事件类状态一律写进 {@code FactLedger}（先记账后派发），求值侧只读事实；本类不再维护进程内镜像。
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
        // 单机同进程换存档：静态相机中心 / 锚点 / 虚拟中心不得继承上一份存档的玩家
        CameraCenterManager.INSTANCE.clearAll();
        CameraViewManager.INSTANCE.clearAll();
        ScriptManager.INSTANCE.loadAll(server);
        FactLedger.INSTANCE.initialize(server);
        TriggerEngine.INSTANCE.initialize();
        ScriptManager.INSTANCE.registerAllTriggers();
    }

    /** 服务端停止：把全部玩家事实落盘（关服前最后一次持久化），再清空相机中心（不做 move）。 */
    public static void onServerStopping(MinecraftServer server) {
        FactLedger.INSTANCE.saveAll();
        CameraCenterManager.INSTANCE.clearAll();
        CameraViewManager.INSTANCE.clearAll();
    }

    /** 维度卸载：清掉该维度的相机中心 / 锚点 / 虚拟中心与跨维度视图，不做 move。 */
    public static void onLevelUnload(ServerLevel level) {
        CameraCenterManager.INSTANCE.onLevelUnload(level);
        CameraViewManager.INSTANCE.onLevelUnload(level);
    }

    /** 玩家加入：清空残留音频监听状态，载入事实，记 login 事实，再补发 login 事件。 */
    public static void onPlayerJoin(ServerPlayer serverPlayer) {
        com.immersivecinematics.immersive_cinematics.trigger.server.AudioListenerServerState.clear(serverPlayer.getUUID());
        FactLedger.INSTANCE.loadForPlayer(serverPlayer.getUUID());
        recordEvent(serverPlayer, FactType.LOGIN, "");
        // 跨维度视图建 ClientLevel 需要「维度 id → 维度类型」表；加入时下发一次
        CameraViewManager.INSTANCE.sendDimensionInfo(serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("login", serverPlayer);
    }

    /** 玩家退出：清理该 UUID 的相机中心 / 音频 / 事实与库存采样基线，避免重连后读到上一轮状态。 */
    public static void onPlayerQuit(ServerPlayer serverPlayer) {
        UUID uuid = serverPlayer.getUUID();
        CameraCenterManager.INSTANCE.onDisconnect(uuid, serverPlayer);
        CameraViewManager.INSTANCE.onPlayerQuit(serverPlayer);
        com.immersivecinematics.immersive_cinematics.trigger.server.AudioListenerServerState.clear(uuid);
        FactLedger.INSTANCE.unloadForPlayer(uuid);
        // 事件类状态全部在事实层，随 unloadForPlayer 一起移除；只剩库存采样基线（不是历史事实）
        Evaluators.InventoryTracker.clear(uuid);
        // 运动采样与区域边沿是进程内状态：重连后从零开始（否则会把断线前后的两点连成一段扫掠）。
        PlayerMotion.INSTANCE.remove(uuid);
        TriggerEngine.INSTANCE.clearPlayer(uuid);
    }

    /** 每 tick 驱动触发引擎、脚本事件队列与相机中心超时清理。 */
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
            CameraCenterManager.INSTANCE.tick(server);
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Server", "tick:camera-center", 5000L,
                    "服务端 tick 相机中心超时清理异常（本 tick 跳过该子系统，其余子系统继续）", e);
        }
        try {
            CameraViewManager.INSTANCE.tick(server);
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Server", "tick:camera-view", 5000L,
                    "服务端 tick 跨维度视图流异常（本 tick 跳过该子系统，其余子系统继续）", e);
        }
    }

    /** 命令注册：转交 {@code CinematicCommand}，命令树只在服务端构建一次。 */
    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.immersivecinematics.immersive_cinematics.command.CinematicCommand.register(dispatcher);
    }

    /** 成就达成：记 ADVANCEMENT 事实后派发 advancement 事件。 */
    public static void onPlayerAdvancement(ServerPlayer player, Advancement advancement) {
        recordEvent(player, FactType.ADVANCEMENT, advancement.getId().toString());
        TriggerEngine.INSTANCE.onGameEvent("advancement", player);
    }

    /** 实体死亡：击杀者记 ENTITY_KILL、死者记 DEATH；两条分支独立判定（PvP 双方都触发）。 */
    public static void onLivingDeath(LivingEntity entity, DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            FactLedger.INSTANCE.record(player.getUUID(), Fact.scene(FactType.ENTITY_KILL,
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    sceneOf((ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ()),
                    player.server.getTickCount()));
            TriggerEngine.INSTANCE.onGameEvent("entity_kill", player);
        }
        // PvP 双触发：击杀者走上面的 entity_kill、死者走下面的 death，两条分支独立（不是 else-if）
        if (entity instanceof ServerPlayer victim) {
            FactLedger.INSTANCE.record(victim.getUUID(), Fact.scene(FactType.DEATH, "",
                    sceneOf(victim.serverLevel(), victim.getX(), victim.getY(), victim.getZ()),
                    victim.server.getTickCount()));
            TriggerEngine.INSTANCE.onGameEvent("death", victim);
        }
    }

    /** 右键方块：记 INTERACT_BLOCK（方块 id + 手持物）后派发 block_interact / item_on_interact。 */
    public static void onRightClickBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        recordEvent(serverPlayer, FactType.INTERACT_BLOCK,
                BuiltInRegistries.BLOCK.getKey(serverPlayer.level().getBlockState(pos).getBlock()).toString(),
                itemId(serverPlayer.getItemInHand(hand)));
        TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
    }

    /** 左键方块：与右键同口径记账，但只派发 block_interact（左键不构成 item_on_interact）。 */
    public static void onLeftClickBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        recordEvent(serverPlayer, FactType.INTERACT_BLOCK,
                BuiltInRegistries.BLOCK.getKey(serverPlayer.level().getBlockState(pos).getBlock()).toString(),
                itemId(serverPlayer.getItemInHand(hand)));
        TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
    }

    /** 右键实体：记 INTERACT_ENTITY（实体类型 id + 手持物）后派发 entity_interact / item_on_interact。 */
    public static void onInteractEntity(Player player, Entity entity, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        recordEvent(serverPlayer, FactType.INTERACT_ENTITY,
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                itemId(serverPlayer.getItemInHand(hand)));
        TriggerEngine.INSTANCE.onGameEvent("entity_interact", serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
    }

    /** 合成物品：记 ITEM_CRAFT 后派发 item_craft。 */
    public static void onCraftItem(ServerPlayer serverPlayer, ItemStack stack) {
        recordEvent(serverPlayer, FactType.ITEM_CRAFT, itemId(stack));
        TriggerEngine.INSTANCE.onGameEvent("item_craft", serverPlayer);
    }

    /** 右键物品（未命中方块）：记 ITEM_USE 后派发 item_use。 */
    public static void onRightClickItem(ServerPlayer serverPlayer, InteractionHand hand) {
        recordEvent(serverPlayer, FactType.ITEM_USE, itemId(serverPlayer.getItemInHand(hand)));
        TriggerEngine.INSTANCE.onGameEvent("item_use", serverPlayer);
    }

    /** 维度切换：记 DIMENSION_CHANGE（来源维度）后派发 dimension_change。 */
    public static void onChangeDimension(ServerPlayer serverPlayer, ResourceKey<Level> oldLevel, ResourceKey<Level> newLevel) {
        // 换维度 = 一次跳变：代次自增，跨维度的那一段不参与扫掠。
        PlayerMotion.INSTANCE.reset(serverPlayer.getUUID());
        recordEvent(serverPlayer, FactType.DIMENSION_CHANGE, oldLevel.location().toString());
        TriggerEngine.INSTANCE.onGameEvent("dimension_change", serverPlayer);
    }

    /** 拾取物品：记 ITEM_PICKUP 后派发 item_pickup。 */
    public static void onPickupItem(ServerPlayer sp, ItemStack stack) {
        recordEvent(sp, FactType.ITEM_PICKUP, itemId(stack));
        TriggerEngine.INSTANCE.onGameEvent("item_pickup", sp);
    }

    /** 丢弃物品：记 ITEM_DROP 后派发 item_drop。 */
    public static void onDropItem(ServerPlayer sp, ItemStack stack) {
        recordEvent(sp, FactType.ITEM_DROP, itemId(stack));
        TriggerEngine.INSTANCE.onGameEvent("item_drop", sp);
    }

    /**
     * 实体加入世界：玩家投出的投掷物按「瞬时使用」记 ITEM_INSTANT_USE 并派发 item_instant_use。
     * 投掷物不产生右键使用事件，只能在这里补记。
     */
    public static void onEntityAdded(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile proj
                && proj.getOwner() instanceof ServerPlayer sp) {
            recordEvent(sp, FactType.ITEM_INSTANT_USE, itemId(proj.getItem()));
            TriggerEngine.INSTANCE.onGameEvent("item_instant_use", sp);
        }
    }

    // ===== 事实记账 =====

    /** 记一条无详情的事件事实（tick = 当前服务端 tick）。 */
    private static void recordEvent(ServerPlayer player, FactType type, String object) {
        FactLedger.INSTANCE.record(player.getUUID(), Fact.event(type, object, player.server.getTickCount()));
    }

    /** 记一条带 detail 的事件事实（交互目标 + 手持物）。 */
    private static void recordEvent(ServerPlayer player, FactType type, String object, String detail) {
        FactLedger.INSTANCE.record(player.getUUID(),
                Fact.event(type, object, detail, player.server.getTickCount()));
    }

    /** 事件时刻场景快照：维度 / 群系 / 事件坐标（与旧击杀记录逐字同口径）。 */
    private static FactScene sceneOf(ServerLevel level, double x, double y, double z) {
        String dimension = level.dimension().location().toString();
        String biome = level.getBiome(BlockPos.containing(x, y, z)).unwrapKey()
                .map(key -> key.location().toString()).orElse("");
        return new FactScene(dimension, biome, x, y, z);
    }

    /** 物品 id；空手 / 空堆 → 空串。 */
    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 世界保存：事实跟随存档节奏一并落盘（不只依赖关服时的持久化）。 */
    public static void onLevelSave() {
        FactLedger.INSTANCE.saveAll();
    }
}
