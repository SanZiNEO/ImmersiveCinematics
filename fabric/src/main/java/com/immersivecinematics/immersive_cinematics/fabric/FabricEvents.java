package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 事件注册（0.3.5 第7轮去 Arch）。
 */
public final class FabricEvents {

    private FabricEvents() {}

    public static void registerServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerEventHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerEventHandler::onServerStopping);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerEventHandler.onPlayerJoin((ServerPlayer) handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ServerEventHandler.onPlayerQuit((ServerPlayer) handler.player));

        ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ServerEventHandler.onRegisterCommands(dispatcher));

        // TODO Fabric: advancement 事件在 1.20.1 Fabric API 0.92.9 无对应回调，后续用 Mixin 补
        ServerLivingEntityEvents.AFTER_DEATH.register(ServerEventHandler::onLivingDeath);
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ServerEventHandler.onRightClickBlock(player, hand, hitResult.getBlockPos(), hitResult.getDirection());
            return net.minecraft.world.InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            ServerEventHandler.onLeftClickBlock(player, hand, pos, direction);
            return net.minecraft.world.InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            ServerEventHandler.onInteractEntity(player, entity, hand);
            return net.minecraft.world.InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer sp) {
                ServerEventHandler.onRightClickItem(sp, hand);
            }
            return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> ServerEventHandler.onEntityAdded(entity));

        // Craft / pickup / drop / dimension / level save 在 Fabric 上通过 Mixin 或可用事件补齐；
        // 这里先注册已有 API，缺失项由后续 Mixin 补充。
    }

    public static void registerClient() {
        ClientEventHandler.onClientInit();
        ClientEventHandler.registerKeyMappings(KeyBindingHelper::registerKeyBinding);

        ClientTickEvents.END_CLIENT_TICK.register(ClientEventHandler::onClientTick);
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> ClientEventHandler.onRenderHud(graphics));
    }
}
