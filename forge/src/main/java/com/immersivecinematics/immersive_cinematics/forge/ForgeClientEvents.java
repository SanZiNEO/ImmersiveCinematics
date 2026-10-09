package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.ConfigScreen;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.forge.hud.ForgeHudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
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
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
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
 * Forge 客户端事件（0.3.5 第7轮去 Arch）。
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
     * <p>用 {@link RegisterClientReloadListenersEvent}（MOD 总线，Minecraft 构造期触发）而不是
     * {@code FMLClientSetupEvent}——前者注册进客户端资源管理器、此后每次资源重载都会回调。</p>
     */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> CubeLutLoader.clearCache());
    }

    @Mod.EventBusSubscriber(modid = ImmersiveCinematics.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Forge {
        private Forge() {}

        private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/HudHardHide");

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ClientEventHandler.onClientTick(net.minecraft.client.Minecraft.getInstance());
            }
        }

        /**
         * 强硬隐藏模式（{@code meta.hard_hide_hud}）接管点，最高优先级先于其他订阅者执行：
         * 取消 {@code RenderGuiEvent.Pre} → Forge 跳过原版 HUD、全部注册表 overlay 与 {@code Post}，
         * 同时跳过排在本监听器之后的其他订阅者（在 Pre 里自绘的第三方 HUD，如鬼灭冷却条）。
         * 随后由 {@link #renderHardHide} 按白名单重画 + 补画本模组 overlay。
         * <p>normal 模式（未声明该字段）此方法直接返回，行为与改造前一致。
         */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
            if (!isHardHideActive()) return;
            Minecraft mc = Minecraft.getInstance();
            // Forge 下 Minecraft.gui 恒为 ForgeGui；非预期实现时不接管（取消会连重画的机会都没有）
            if (!(mc.gui instanceof ForgeGui forgeGui)) return;
            event.setCanceled(true);
            renderHardHide(mc, forgeGui, event.getGuiGraphics(), event.getPartialTick());
        }

        /**
         * normal 模式的绘制点（黑边 / 字幕 / GIF / 跳过提示）。
         * <p>绘制点按模式分流：hard 模式已在 {@link #onRenderGuiPre} 接管处补画，
         * 且 Pre 被取消后 Forge 不会再发 {@code Post}——此处早退只为让"每帧只画一次"成为显式不变式。
         */
        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            if (isHardHideActive()) return;
            ClientEventHandler.onRenderHud(event.getGuiGraphics());
        }

        /**
         * hard 模式是否正在生效：相机激活（有活跃播放实例）+ 脚本声明 {@code hard_hide_hud: true}
         * + {@code hide_hud} 生效。{@code hide_hud=false} 时无物可藏，不接管——否则会连
         * "白名单判为显示"的第三方自绘 HUD 一起砍掉，违背脚本作者的显式意图。
         */
        private static boolean isHardHideActive() {
            return CameraManager.INSTANCE.isActive()
                    && CinematicController.INSTANCE.isHardHideHud()
                    && CinematicController.INSTANCE.isHideHud();
        }

        /**
         * hard 模式重画：复位左右高度 → 逐层重画白名单判为"显示"的注册表 overlay → 补画本模组 overlay。
         * <p>
         * 判定复用 normal 模式同一套白名单：{@link ForgeHudLayerRegistry#isWorldOverlay} 豁免
         * （暗角 / 望远镜 / 传送门 / 调试文本等世界效果层永不隐藏）+ {@code isLayerHidden(category)}。
         * 不补发 {@code RenderGuiOverlayEvent.Pre/Post}（理由见 {@code plans/0.3.6/hud-hard-hide.md} 落地标注）。
         * <p>
         * 与 {@code ForgeGui.render} 的差异（有意为之）：不设 {@code screenWidth/Height}（ForgeGui 在 Pre 之前
         * 已设）、不重新播种 {@code random}（`Gui.random` 为 protected，跨包不可访问；影响仅限食物图标抖动）。
         */
        private static void renderHardHide(Minecraft mc, ForgeGui gui, GuiGraphics graphics, float partialTick) {
            // ForgeGui.render 在 Pre 之前已复位，这里再复位一次是防御：更早的 HIGHEST 订阅者可能改过这两个公开字段
            gui.leftHeight = 39;
            gui.rightHeight = 39;

            int width = mc.getWindow().getGuiScaledWidth();
            int height = mc.getWindow().getGuiScaledHeight();
            for (NamedGuiOverlay entry : GuiOverlayManager.getOverlays()) {
                ResourceLocation overlayId = entry.id();
                if (!ForgeHudLayerRegistry.isWorldOverlay(overlayId)
                        && CinematicController.INSTANCE.isLayerHidden(ForgeHudLayerRegistry.categoryOf(overlayId))) {
                    continue;
                }
                try {
                    entry.overlay().render(gui, graphics, partialTick, width, height);
                } catch (Exception e) {
                    // 与 ForgeGui 同款单层隔离：一个第三方 overlay 抛异常不应拖垮整段 HUD
                    LOGGER.error("hard hide: overlay '{}' 渲染失败", overlayId, e);
                }
            }

            // 对齐 ForgeGui.render 在 overlay 循环后的着色器复位，再补画本模组 overlay（normal 模式下由 Post 承担）
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            ClientEventHandler.onRenderHud(graphics);
        }

        @SubscribeEvent
        public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
            if (!CameraManager.INSTANCE.isActive()) return;
            ResourceLocation overlayId = event.getOverlay().id();
            if (ForgeHudLayerRegistry.isWorldOverlay(overlayId)) return;
            String category = ForgeHudLayerRegistry.categoryOf(overlayId);
            if (CinematicController.INSTANCE.isLayerHidden(category)) {
                event.setCanceled(true);
            }
        }
    }
}
