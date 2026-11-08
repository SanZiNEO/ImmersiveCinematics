package com.immersivecinematics.immersive_cinematics.fabric.hud;

import com.immersivecinematics.immersive_cinematics.control.CinematicController;

/**
 * Fabric 侧画面层接管条件单点。
 *
 * <p>条件本身不在这里定义：统一走 {@link CinematicController#isScreenLayerTakeoverActive()}
 * （有活跃播放实例 + 任一画面层判隐藏），与 Forge 的接管点共用同一入口。
 * 本类只做平台侧转发，不含渲染逻辑——重画顺序在 {@code FabricGuiTakeoverMixin}，
 * 自定义层表在 {@link FabricHudLayerRegistry}。</p>
 */
public final class FabricHudTakeover {

    private FabricHudTakeover() {}

    /** 是否应当取消 {@code Gui.render} 整帧并按白名单重画。 */
    public static boolean isActive() {
        return CinematicController.INSTANCE.isScreenLayerTakeoverActive();
    }
}
