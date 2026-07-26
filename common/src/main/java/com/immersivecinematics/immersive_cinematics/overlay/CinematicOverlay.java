package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 电影覆盖层 — 客户端 HUD 渲染入口
 * <p>
 * 作为分层覆盖系统（{@link OverlayManager}）的渲染入口，在 {@code ClientGuiEvent.RENDER_HUD} 事件中注册（Phase 7）。
 * <p>
 * 渲染流程：
 * <pre>
 * ClientGuiEvent.RENDER_HUD → CinematicOverlay.render() → OverlayManager.render()
 *     → LetterboxLayer.render()  (zIndex=0, 最底层)
 *     → TextLayer.render()       (zIndex=100, 后续扩展)
 *     → VideoLayer.render()      (zIndex=200, 后续扩展)
 * </pre>
 */
public class CinematicOverlay {

    /** overlay 唯一标识符，用于白名单放行 */
    public static final String OVERLAY_ID = "cinematic_overlay";

    /**
     * HUD 渲染回调。
     * <p>
     * 在相机激活或有层正在动画时渲染覆盖层，否则自动跳过。
     * 通过 {@code ClientGuiEvent.RENDER_HUD} 注册（Phase 7）。
     */
    public static void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        boolean cameraActive = CameraManager.INSTANCE.isActive();
        boolean overlayAnimating = OverlayManager.INSTANCE.isAnimating();

        if (!cameraActive && !overlayAnimating) {
            return;
        }

        // 只负责渲染，动画驱动由 CameraManager.onRenderFrame() 负责
        OverlayManager.INSTANCE.render(guiGraphics, screenWidth, screenHeight);
    }
}
