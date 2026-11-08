package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 服务端事件注册：把 Fabric API 回调接到平台无关的 {@link ServerEventHandler}。
 *
 * <p>客户端事件在 {@link com.immersivecinematics.immersive_cinematics.fabric.client.FabricClientEvents}：
 * 本类会被专用服务器加载，不得引用任何 client-only 类型（否则服务端启动即崩）。</p>
 */
public final class FabricEvents {

    private FabricEvents() {}

    public static void registerServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerEventHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerEventHandler::onServerStopping);
        ServerWorldEvents.UNLOAD.register((server, world) -> ServerEventHandler.onLevelUnload(world));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerEventHandler.onPlayerJoin((ServerPlayer) handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ServerEventHandler.onPlayerQuit((ServerPlayer) handler.player));

        ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ServerEventHandler.onRegisterCommands(dispatcher));

        // advancement 无 Fabric API 回调（0.92.9），由 PlayerAdvancementsMixin 拦截 award 接线
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
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                ServerEventHandler.onChangeDimension((ServerPlayer) player, origin.dimension(), destination.dimension()));

        // craft / pickup / drop / level save 同样无 Fabric API 回调，由 fabric 侧对应 Mixin 接线
    }
}
