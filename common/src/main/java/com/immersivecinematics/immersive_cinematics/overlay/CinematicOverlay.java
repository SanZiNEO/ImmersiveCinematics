package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 电影覆盖层 —— 客户端 HUD 渲染入口。
 *
 * 作为分层覆盖系统（{@link OverlayManager}）的渲染入口，在 {@code ClientGuiEvent.RENDER_HUD} 事件中注册。
 * 渲染链路：{@code ClientGuiEvent.RENDER_HUD} → {@link #render} → {@link OverlayManager#render} →
 * 各实例的层按 zIndex 升序绘制（黑边 0 → 脚本层 10+）。
 */
public class CinematicOverlay {

    /** overlay 唯一标识符，用于白名单放行 */
    public static final String OVERLAY_ID = "cinematic_overlay";

    /**
     * HUD 渲染回调：相机激活或有层正在动画时渲染覆盖层，否则跳过。
     */
    public static void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        boolean cameraActive = CameraManager.INSTANCE.isActive();
        boolean overlayAnimating = OverlayManager.INSTANCE.isAnyAnimating();

        if (!cameraActive && !overlayAnimating) {
            return;
        }

        // 只负责渲染，动画驱动由 CameraManager.onRenderFrame() 负责
        OverlayManager.INSTANCE.render(guiGraphics, screenWidth, screenHeight);
    }
}
