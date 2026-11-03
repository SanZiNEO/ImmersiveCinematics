package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.api.HudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import com.immersivecinematics.immersive_cinematics.overlay.ColorMaskRenderer;
import com.immersivecinematics.immersive_cinematics.script.CubeLutLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabric 事件注册：把 Fabric API 回调接到平台无关的 {@link ServerEventHandler} 与 {@link ClientEventHandler}。
 */
public final class FabricEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/HudLayer");
    /** 边界说明只打一次：整合包可能批量注册，逐条刷屏会把真正有用的日志埋掉 */
    private static final AtomicBoolean HUD_LAYER_BOUNDARY_LOGGED = new AtomicBoolean();

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

    public static void registerClient() {
        ClientEventHandler.onClientInit();
        // 门面回调只用来交代平台边界：Fabric 的 HUD 回调不可取消、没有 overlay 注册表，
        // 注册转不成任何接管能力，写表后也就没有后续动作可做。
        HudLayerRegistry.install(FabricEvents::onHudLayerRegistered);
        ClientEventHandler.registerKeyMappings(KeyBindingHelper::registerKeyBinding);

        ClientTickEvents.END_CLIENT_TICK.register(ClientEventHandler::onClientTick);
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> ClientEventHandler.onRenderHud(graphics));

        // F3+T 资源重载：清空 LUT 解析缓存（解析缓存 + 负缓存 + clip 引用表），下次取用按新文件重新解析
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return new ResourceLocation(ImmersiveCinematics.MOD_ID, "lut_cache");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager resourceManager) {
                        CubeLutLoader.clearCache();
                        // 颜色遮罩的 core shader / scratch / 各层 LUT 由渲染侧按代次重建；
                        // 本回调可能不在渲染线程，invalidate 内部会排队到渲染线程，绝不直接碰 GL。
                        ColorMaskRenderer.invalidate();
                        // 跨维度视图的渲染器描边链在渲染线程重建（本回调可能不在渲染线程，只置位）
                        DimensionViews.requestResourceReload();
                    }
                });
    }

    /**
     * 门面注册回调：首次注册时说明 Fabric 边界，之后静默。
     * Fabric 没有 hard 模式，也没有 Forge 的 overlay 注册表——注册能影响的只有普通层过滤
     * （{@code FabricHudVisibility.isHidden} 与固定分类），分类名本身仅作记录。
     */
    private static void onHudLayerRegistered(ResourceLocation overlayId, String category) {
        if (!HUD_LAYER_BOUNDARY_LOGGED.compareAndSet(false, true)) return;
        LOGGER.info("HUD 层注册 '{}'（分类 '{}'）：Fabric 无 hard_hide_hud、无 Forge overlay 注册表；"
                        + "注册只影响普通层过滤（FabricHudVisibility.isHidden 与固定分类），分类仅作记录",
                overlayId, category);
    }
}
