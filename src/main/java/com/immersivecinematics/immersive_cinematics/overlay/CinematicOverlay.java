package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.Immersive_cinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 电影覆盖层 — Forge GuiOverlay 注册入口
 * <p>
 * 作为 Forge GUI overlay 系统的注册入口，将我们的分层覆盖系统
 * （{@link OverlayManager}）接入 Minecraft 的 HUD 渲染管线。
 * <p>
 * 注册为最顶层 overlay（registerAboveAll），确保覆盖在所有其他 HUD 之上。
 * 通过 {@link HudOverlayHandler} 的白名单机制放行，其他原版/模组 HUD 在电影模式下被隐藏。
 * <p>
 * 渲染流程：
 * <pre>
 * Forge HUD 管线 → CinematicOverlay.render() → OverlayManager.render()
 *     → LetterboxLayer.render()  (zIndex=0, 最底层)
 *     → TextLayer.render()       (zIndex=100, 后续扩展)
 *     → VideoLayer.render()      (zIndex=200, 后续扩展)
 * </pre>
 */
public class CinematicOverlay {

    /** overlay 唯一标识符，用于白名单放行 */
    public static final String OVERLAY_ID = "cinematic_overlay";

    /**
     * 注册电影覆盖层到 Forge GUI overlay 系统
     * <p>
     * 在模组构造函数中通过 MOD 事件总线调用：
     * <pre>
     * modEventBus.addListener(CinematicOverlay::onRegisterGuiOverlays);
     * </pre>
     *
     * @param event Forge GUI overlay 注册事件
     */
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll(OVERLAY_ID, CINEMATIC_OVERLAY);
    }

    /**
     * Forge IGuiOverlay 实现 — 委托给 OverlayManager
     * <p>
     * 只在相机激活时渲染覆盖层，停用时自动跳过（性能零开销）。
     */
    private static final IGuiOverlay CINEMATIC_OVERLAY = (overlay, guiGraphics, partialTick, screenWidth, screenHeight) -> {
        if (!CameraManager.INSTANCE.isActive()) return;

        OverlayManager.INSTANCE.render(guiGraphics, screenWidth, screenHeight);
    };
}
