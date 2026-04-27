package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

import java.util.Set;

/**
 * 电影模式 HUD 控制器 — 白名单机制
 *
 * 当 CameraManager 处于激活状态时，默认取消所有 overlay 渲染，
 * 只放行白名单中的 overlay。这样既能隐藏原版 HUD，
 * 也能自动屏蔽其他模组注册的 HUD overlay。
 *
 * 白名单中的 overlay 在电影模式下仍然正常渲染，
 * 后续如果需要在 HUD 层显示自定义文本，可以注册自己的 overlay 并加入白名单。
 */
public class HudOverlayHandler {

    /**
     * 电影模式下允许显示的 overlay 白名单
     * 使用 ResourceLocation 标识，兼容原版和模组 overlay
     *
     * 目前白名单为空 — 电影模式下隐藏所有 HUD
     * 后续可以添加自定义 overlay 到白名单，例如：
     *   VanillaGuiOverlay.SUBTITLES.id()
     */
    private static final Set<ResourceLocation> ALLOWED_OVERLAYS = Set.of(
            // 白名单 — 电影模式下允许显示的 overlay
            // 目前为空：电影模式下完全隐藏所有 HUD
    );

    /**
     * 处理 GUI overlay 渲染事件
     * 在每个 overlay 渲染前检查：如果电影模式激活且该 overlay 不在白名单中，则取消渲染
     */
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!CameraManager.INSTANCE.isActive()) {
            return;
        }

        // 白名单检查：只在白名单中的 overlay 才允许渲染
        if (!ALLOWED_OVERLAYS.contains(event.getOverlay().id())) {
            event.setCanceled(true);
        }
    }
}
