package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.client.ConfigScreen;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.forge.hud.ForgeHudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.immersivecinematics.immersive_cinematics.overlay.ColorMaskRenderer;
import com.immersivecinematics.immersive_cinematics.script.CubeLutLoader;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.GuiOverlayManager;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 客户端事件：MOD 总线管注册期回调，FORGE 总线管每帧 HUD 与 tick。
 */
@Mod.EventBusSubscriber(modid = ImmersiveCinematics.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeClientEvents {

    private ForgeClientEvents() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ClientEventHandler.onClientInit();
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new ConfigScreen(parent)));
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        ClientEventHandler.registerKeyMappings(event::register);
    }

    /**
     * F3+T 资源重载：清空 LUT 解析缓存（解析缓存 + 负缓存 + clip 引用表），下次取用按新文件重新解析。
     * 用 {@link RegisterClientReloadListenersEvent}（MOD 总线，Minecraft 构造期触发）而不是
     * {@code FMLClientSetupEvent}——前者注册进客户端资源管理器、此后每次资源重载都会回调。
     */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            CubeLutLoader.clearCache();
            // 颜色遮罩的 core shader / scratch / 各层 LUT 由渲染侧按代次重建；
            // 本回调可能不在渲染线程，invalidate 内部会排队到渲染线程，绝不直接碰 GL。
            ColorMaskRenderer.invalidate();
            // 跨维度视图的渲染器描边链在渲染线程重建（本回调可能不在渲染线程，只置位）
            DimensionViews.requestResourceReload();
        });
    }

    @Mod.EventBusSubscriber(modid = ImmersiveCinematics.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Forge {
        private Forge() {}

        private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScreenLayers");

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ClientEventHandler.onClientTick(net.minecraft.client.Minecraft.getInstance());
            }
        }

        /**
         * 画面层接管点（唯一模式：任一隐藏层 → 整帧清除 + 白名单重画），最高优先级先于其他订阅者执行：
         * 取消 {@code RenderGuiEvent.Pre} → Forge 跳过原版 HUD、
         * 全部注册表 overlay 与 {@code Post}，同时跳过排在本监听器之后的其他订阅者
         * （在 Pre 里自绘的第三方 HUD，如鬼灭冷却条）。
         * 随后由 {@link #renderTakeover} 按画面层判定重画 + 补画本模组 overlay。
         *
         * 无隐藏层时此方法直接返回，不接管。
         */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
            if (!isTakeoverActive()) return;
            Minecraft mc = Minecraft.getInstance();
            // Forge 下 Minecraft.gui 恒为 ForgeGui；非预期实现时不接管（取消会连重画的机会都没有）
            if (!(mc.gui instanceof ForgeGui forgeGui)) return;
            event.setCanceled(true);
            renderTakeover(mc, forgeGui, event.getGuiGraphics(), event.getPartialTick());
        }

        /**
         * 未接管时的绘制点（黑边 / 字幕 / GIF / 跳过提示）。
         *
         * 接管已在 {@link #onRenderGuiPre} 处补画，且 Pre 被取消后 Forge 不会再发 {@code Post}——
         * 此处早退只为让"每帧只画一次"成为显式不变式。
         */
        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            if (isTakeoverActive()) return;
            ClientEventHandler.onRenderHud(event.getGuiGraphics());
        }

        /**
         * 画面层接管是否正在生效：条件统一由 {@link CinematicController#isScreenLayerTakeoverActive()}
         * 判定（活跃实例 + 任一画面层判隐藏），Fabric 复用同一入口。
         */
        private static boolean isTakeoverActive() {
            return CinematicController.INSTANCE.isScreenLayerTakeoverActive();
        }

        /**
         * 接管重画：复位左右高度 → 逐层重画画面层判为"显示"的注册表 overlay → 补画本模组 overlay；
         * 每层用 {@code isLayerHidden(layerOf(id), categoryOf(id))} 判定（规范层名优先，旧分类名兜底）。
         * 不补发 {@code RenderGuiOverlayEvent.Pre/Post}：构造器 {@code @ApiStatus.Internal}
         * 且 {@code NamedGuiOverlay} 无法自建，逐层过滤已经在本方法内完成，补发只会多派发两轮事件。
         * 与 {@code ForgeGui.render} 的差异：不设 screenWidth/Height（Pre 前已设）、不重播 random（字段不可访问，仅影响食物图标抖动）。
         */
        private static void renderTakeover(Minecraft mc, ForgeGui gui, GuiGraphics graphics, float partialTick) {
            // ForgeGui.render 在 Pre 之前已复位，这里再复位一次是防御：更早的 HIGHEST 订阅者可能改过这两个公开字段
            gui.leftHeight = 39;
            gui.rightHeight = 39;

            int width = mc.getWindow().getGuiScaledWidth();
            int height = mc.getWindow().getGuiScaledHeight();
            for (NamedGuiOverlay entry : GuiOverlayManager.getOverlays()) {
                ResourceLocation overlayId = entry.id();
                if (!ForgeHudLayerRegistry.isWorldOverlay(overlayId)) {
                    String layer = ForgeHudLayerRegistry.layerOf(overlayId);
                    String category = ForgeHudLayerRegistry.categoryOf(overlayId);
                    if (CinematicController.INSTANCE.isLayerHidden(layer, category)) {
                        continue;
                    }
                }
                try {
                    entry.overlay().render(gui, graphics, partialTick, width, height);
                } catch (Exception e) {
                    // 与 ForgeGui 同款单层隔离：一个第三方 overlay 抛异常不应拖垮整段 HUD
                    LOGGER.error("takeover: overlay '{}' 渲染失败", overlayId, e);
                }
            }

            // 对齐 ForgeGui.render 在 overlay 循环后的着色器复位，再补画本模组 overlay（未接管时由 Post 承担）
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            ClientEventHandler.onRenderHud(graphics);
        }
    }
}
