package com.immersivecinematics.immersive_cinematics.fabric.client;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.api.HudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.client.ConfigScreen;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.immersivecinematics.immersive_cinematics.overlay.ColorMaskRenderer;
import com.immersivecinematics.immersive_cinematics.script.CubeLutLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabric 客户端事件注册。
 *
 * <p>本类只能从 {@link ImmersiveCinematicsFabricClient}（client entrypoint）触达：它引用的
 * {@code ScreenEvents} / {@code PauseScreen} / {@code ConfigScreen} 等是 client-only 类型，
 * 放进 {@link com.immersivecinematics.immersive_cinematics.fabric.FabricEvents} 会让专用服务器
 * 在加载该类时尝试加载 {@code net.minecraft.client.gui.screens.Screen} 并启动失败。</p>
 */
public final class FabricClientEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/HudLayer");
    /** 边界说明只打一次：整合包可能批量注册，逐条刷屏会把真正有用的日志埋掉 */
    private static final AtomicBoolean HUD_LAYER_BOUNDARY_LOGGED = new AtomicBoolean();

    private FabricClientEvents() {}

    public static void register() {
        ClientEventHandler.onClientInit();
        // 平台回调只用来在首次注册时交代一次平台边界；整帧接管由 FabricGuiTakeoverMixin 完成。
        HudLayerRegistry.install(new HudLayerRegistry.Platform() {
            @Override
            public void onRegistered(ResourceLocation overlayId, String category) {
                onHudLayerRegistered(overlayId, category);
            }
        });
        ClientEventHandler.registerKeyMappings(KeyBindingHelper::registerKeyBinding);

        ClientTickEvents.END_CLIENT_TICK.register(ClientEventHandler::onClientTick);
        // 未接管时的绘制点：Gui.render 正常执行，回调在原版 HUD 之后派发
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> ClientEventHandler.onRenderHud(graphics));
        // 接管时 Gui.render 被整帧取消，本回调不会派发；重画末尾由 FabricGuiTakeoverMixin 补画

        // 暂停菜单的配置入口：每次 init 都会重建按钮，不会重复累积；非标准暂停菜单没有网格按钮则跳过
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PauseScreen)) return;
            if (Screens.getButtons(screen).isEmpty()) return;
            Screens.getButtons(screen).add(Button.builder(
                            Component.translatable("config.immersive_cinematics.open"),
                            button -> client.setScreen(new ConfigScreen(screen)))
                    .bounds(scaledWidth - 110, 8, 100, 20)
                    .build());
        });

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
     * Fabric 通过 {@code Gui.render} 整帧接管 + 自定义层表实现画面层控制；原生 {@code HudRenderCallback}
     * 在接管时随整帧取消，不参与白名单——要参与必须用
     * {@link com.immersivecinematics.immersive_cinematics.fabric.hud.FabricHudLayerRegistry#register}。
     */
    private static void onHudLayerRegistered(ResourceLocation overlayId, String category) {
        if (!HUD_LAYER_BOUNDARY_LOGGED.compareAndSet(false, true)) return;
        LOGGER.info("HUD 层注册 '{}'（分类 '{}'）：Fabric 通过 Gui.render 整帧接管 + 自定义层表实现画面层控制；"
                        + "原生 HudRenderCallback 在接管时随整帧取消",
                overlayId, category);
    }
}
