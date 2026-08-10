package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CTriggerStateSyncPacket;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.PlayerTriggerState;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

public class ServerEventHandler {

    public static void register() {
        // ===== 服务器生命周期 =====

        LifecycleEvent.SERVER_STARTED.register(server -> {
            ScriptManager.INSTANCE.loadAll(server);
            TriggerStateStore.INSTANCE.initialize(server);
            TriggerEngine.INSTANCE.initialize();
            ScriptManager.INSTANCE.registerAllTriggers();
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            TriggerStateStore.INSTANCE.saveAll();
        });

        // ===== 玩家事件 =====

        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (!(player instanceof ServerPlayer)) return;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            TriggerStateStore.INSTANCE.loadForPlayer(serverPlayer.getUUID());
            // JOIN 补发：把已加载的触发/完成状态同步给客户端
            PlayerTriggerState joinState = TriggerStateStore.INSTANCE.getOrCreate(serverPlayer.getUUID());
            S2CTriggerStateSyncPacket.send(
                    serverPlayer, joinState.getTriggeredScripts(), joinState.getCompletedScripts());
            TriggerEngine.INSTANCE.onGameEvent("login", serverPlayer);
        });

        PlayerEvent.PLAYER_QUIT.register(player -> {
            if (!(player instanceof ServerPlayer)) return;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            UUID uuid = serverPlayer.getUUID();
            TriggerStateStore.INSTANCE.unloadForPlayer(uuid);
            Evaluators.KillTracker.clear(uuid);
            Evaluators.AdvancementTracker.clear(uuid);
            Evaluators.InteractTracker.clear(uuid);
            Evaluators.CraftTracker.clear(uuid);
            Evaluators.UseItemTracker.clear(uuid);
            Evaluators.PickupDropTracker.clear(uuid);
            Evaluators.InventoryTracker.clear(uuid);
            Evaluators.DimensionTracker.clear(uuid);
        });

        // ===== 服务器 Tick =====

        TickEvent.SERVER_POST.register(server -> {
            TriggerEngine.INSTANCE.onServerTick(server);
            ScriptEventManager.INSTANCE.onServerTick(server);
        });

        // ===== 命令注册 =====

        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            if (selection == Commands.CommandSelection.DEDICATED || selection == Commands.CommandSelection.INTEGRATED) {
                com.immersivecinematics.immersive_cinematics.command.CinematicCommand.register(dispatcher);
            }
        });

        // ===== 事件驱动触发器 =====

        PlayerEvent.PLAYER_ADVANCEMENT.register((player, advancement) -> {
            Evaluators.AdvancementTracker.record(player, advancement.getId().toString());
            TriggerEngine.INSTANCE.onGameEvent("advancement", player);
        });

        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (source.getEntity() instanceof ServerPlayer) {
                ServerPlayer player = (ServerPlayer) source.getEntity();
                // 记录击杀时刻的维度/群系/坐标（被杀实体的位置，供场景条件判定）
                Evaluators.KillTracker.record(player, entity.getType(),
                        (ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ());
                TriggerEngine.INSTANCE.onGameEvent("entity_kill", player);
            }
            return EventResult.pass();
        });

        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (!(player instanceof ServerPlayer)) return EventResult.pass();
            ServerPlayer serverPlayer = (ServerPlayer) player;
            Evaluators.InteractTracker.recordBlock(serverPlayer.getUUID(),
                    serverPlayer.level().getBlockState(pos));
            Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                    serverPlayer.getItemInHand(hand));
            TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
            TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
            return EventResult.pass();
        });

        InteractionEvent.LEFT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (!(player instanceof ServerPlayer)) return EventResult.pass();
            ServerPlayer serverPlayer = (ServerPlayer) player;
            Evaluators.InteractTracker.recordBlock(serverPlayer.getUUID(),
                    serverPlayer.level().getBlockState(pos));
            Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                    serverPlayer.getItemInHand(hand));
            TriggerEngine.INSTANCE.onGameEvent("block_interact", serverPlayer);
            return EventResult.pass();
        });

        InteractionEvent.INTERACT_ENTITY.register((player, entity, hand) -> {
            if (!(player instanceof ServerPlayer)) return EventResult.pass();
            ServerPlayer serverPlayer = (ServerPlayer) player;
            Evaluators.InteractTracker.recordEntity(serverPlayer.getUUID(), entity.getType());
            Evaluators.InteractTracker.recordInteractionItem(serverPlayer.getUUID(),
                    serverPlayer.getItemInHand(hand));
            TriggerEngine.INSTANCE.onGameEvent("entity_interact", serverPlayer);
            TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
            return EventResult.pass();
        });

        PlayerEvent.CRAFT_ITEM.register((player, stack, container) -> {
            if (!(player instanceof ServerPlayer)) return;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            Evaluators.CraftTracker.record(serverPlayer, stack);
            TriggerEngine.INSTANCE.onGameEvent("item_craft", serverPlayer);
        });

        InteractionEvent.RIGHT_CLICK_ITEM.register((player, hand) -> {
            if (!(player instanceof ServerPlayer)) return CompoundEventResult.pass();
            ServerPlayer serverPlayer = (ServerPlayer) player;
            Evaluators.UseItemTracker.recordUsed(serverPlayer, serverPlayer.getItemInHand(hand));
            TriggerEngine.INSTANCE.onGameEvent("item_use", serverPlayer);
            return CompoundEventResult.pass();
        });

        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) -> {
            if (!(player instanceof ServerPlayer)) return;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            // 记录来源维度，供 dimension_change 的 from_dimension 过滤求值
            Evaluators.DimensionTracker.record(serverPlayer.getUUID(),
                    oldLevel.location().toString());
            TriggerEngine.INSTANCE.onGameEvent("dimension_change", serverPlayer);
        });

        PlayerEvent.PICKUP_ITEM_POST.register((player, entity, stack) -> {
            if (player instanceof ServerPlayer sp) {
                Evaluators.PickupDropTracker.recordPickup(sp, stack);
                TriggerEngine.INSTANCE.onGameEvent("item_pickup", sp);
            }
        });

        PlayerEvent.DROP_ITEM.register((player, entity) -> {
            if (player instanceof ServerPlayer sp) {
                Evaluators.PickupDropTracker.recordDrop(sp, entity.getItem());
                TriggerEngine.INSTANCE.onGameEvent("item_drop", sp);
            }
            return EventResult.pass();
        });

        // ===== 特殊事件替代方案 =====

        // item_consume / item_release / item_use_interrupt: Mixin LivingEntity
        // （completeUsingItem / releaseUsingItem，见 mixin/ItemUseMixin）
        // item_instant_use: 投掷物实体加入时判定（雪球/鸡蛋/珍珠/药水/经验瓶）
        EntityEvent.ADD.register((entity, level) -> {
            if (entity instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile proj
                    && proj.getOwner() instanceof ServerPlayer sp) {
                Evaluators.UseItemTracker.recordInstantUse(sp, proj.getItem());
                TriggerEngine.INSTANCE.onGameEvent("item_instant_use", sp);
            }
            return EventResult.pass();
        });

        // world_save: 通过 SERVER_LEVEL_SAVE 兜底
        LifecycleEvent.SERVER_LEVEL_SAVE.register(level -> {
            TriggerStateStore.INSTANCE.saveAll();
        });
    }
}
