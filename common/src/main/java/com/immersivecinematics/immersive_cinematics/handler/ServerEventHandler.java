package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CTriggerStateSyncPacket;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.PlayerTriggerState;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;
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
 * 服务端事件处理（0.3.5 第7轮去 Arch）。
 * <p>
 * 只保留纯逻辑；平台（Fabric/Forge）负责把原生事件转发到这里。
 */
public final class ServerEventHandler {

    private ServerEventHandler() {}

    public static void onServerStarted(MinecraftServer server) {
        ScriptManager.INSTANCE.loadAll(server);
        TriggerStateStore.INSTANCE.initialize(server);
        TriggerEngine.INSTANCE.initialize();
        ScriptManager.INSTANCE.registerAllTriggers();
    }

    public static void onServerStopping(MinecraftServer server) {
        TriggerStateStore.INSTANCE.saveAll();
    }

    public static void onPlayerJoin(ServerPlayer serverPlayer) {
        TriggerStateStore.INSTANCE.loadForPlayer(serverPlayer.getUUID());
        PlayerTriggerState joinState = TriggerStateStore.INSTANCE.getOrCreate(serverPlayer.getUUID());
        S2CTriggerStateSyncPacket.send(
                serverPlayer, joinState.getTriggeredScripts(), joinState.getCompletedScripts());
        TriggerEngine.INSTANCE.onGameEvent("login", serverPlayer);
    }

    public static void onPlayerQuit(ServerPlayer serverPlayer) {
        UUID uuid = serverPlayer.getUUID();
        com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager.INSTANCE.onDisconnect(uuid, serverPlayer);
        TriggerStateStore.INSTANCE.unloadForPlayer(uuid);
        Evaluators.KillTracker.clear(uuid);
        Evaluators.AdvancementTracker.clear(uuid);
        Evaluators.InteractTracker.clear(uuid);
        Evaluators.CraftTracker.clear(uuid);
        Evaluators.UseItemTracker.clear(uuid);
        Evaluators.PickupDropTracker.clear(uuid);
        Evaluators.InventoryTracker.clear(uuid);
        Evaluators.DimensionTracker.clear(uuid);
    }

    public static void onServerTick(MinecraftServer server) {
        TriggerEngine.INSTANCE.onServerTick(server);
        ScriptEventManager.INSTANCE.onServerTick(server);
        com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager.INSTANCE.tick();
    }

    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.immersivecinematics.immersive_cinematics.command.CinematicCommand.register(dispatcher);
    }

    public static void onPlayerAdvancement(ServerPlayer player, Advancement advancement) {
        Evaluators.AdvancementTracker.record(player, advancement.getId().toString());
        TriggerEngine.INSTANCE.onGameEvent("advancement", player);
    }

    public static void onLivingDeath(LivingEntity entity, DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            Evaluators.KillTracker.record(player, entity.getType(),
                    (ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ());
            TriggerEngine.INSTANCE.onGameEvent("entity_kill", player);
        }
    }

    public static void onRightClickBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Evaluators.InteractTracker.recordBlock(serverPlayer.getUUID(),
                serverPlayer.level().getBlockState(pos));
        Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
    }

    public static void onLeftClickBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Evaluators.InteractTracker.recordBlock(serverPlayer.getUUID(),
                serverPlayer.level().getBlockState(pos));
        Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
    }

    public static void onInteractEntity(Player player, Entity entity, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Evaluators.InteractTracker.recordEntity(serverPlayer.getUUID(), entity.getType());
        Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("entity_interact", serverPlayer);
        TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
    }

    public static void onCraftItem(ServerPlayer serverPlayer, ItemStack stack) {
        Evaluators.CraftTracker.record(serverPlayer, stack);
        TriggerEngine.INSTANCE.onGameEvent("item_craft", serverPlayer);
    }

    public static void onRightClickItem(ServerPlayer serverPlayer, InteractionHand hand) {
        Evaluators.UseItemTracker.recordUsed(serverPlayer, serverPlayer.getItemInHand(hand));
        TriggerEngine.INSTANCE.onGameEvent("item_use", serverPlayer);
    }

    public static void onChangeDimension(ServerPlayer serverPlayer, ResourceKey<Level> oldLevel, ResourceKey<Level> newLevel) {
        Evaluators.DimensionTracker.record(serverPlayer.getUUID(),
                oldLevel.location().toString());
        TriggerEngine.INSTANCE.onGameEvent("dimension_change", serverPlayer);
    }

    public static void onPickupItem(ServerPlayer sp, ItemStack stack) {
        Evaluators.PickupDropTracker.recordPickup(sp, stack);
        TriggerEngine.INSTANCE.onGameEvent("item_pickup", sp);
    }

    public static void onDropItem(ServerPlayer sp, ItemStack stack) {
        Evaluators.PickupDropTracker.recordDrop(sp, stack);
        TriggerEngine.INSTANCE.onGameEvent("item_drop", sp);
    }

    public static void onEntityAdded(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile proj
                && proj.getOwner() instanceof ServerPlayer sp) {
            Evaluators.UseItemTracker.recordInstantUse(sp, proj.getItem());
            TriggerEngine.INSTANCE.onGameEvent("item_instant_use", sp);
        }
    }

    public static void onLevelSave() {
        TriggerStateStore.INSTANCE.saveAll();
    }
}
