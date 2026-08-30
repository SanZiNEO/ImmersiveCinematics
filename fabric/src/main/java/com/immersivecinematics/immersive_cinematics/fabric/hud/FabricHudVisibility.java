package com.immersivecinematics.immersive_cinematics.fabric.hud;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;

/**
 * Fabric HUD 可见性查询 API：
 * 第三方模组在绘制自己的 HUD 前调用 {@link #isHidden(String)}，即可被脚本白名单控制。
 */
public final class FabricHudVisibility {

    private FabricHudVisibility() {}

    /** 当前是否有电影脚本激活 */
    public static boolean isActive() {
        return CameraManager.INSTANCE.isActive();
    }

    /** 指定 HUD 层是否应隐藏；未注册时按 layerId 作为动态分类 */
    public static boolean isHidden(String layerId) {
        if (!CameraManager.INSTANCE.isActive()) return false;
        String category = FabricHudLayerRegistry.categoryOf(layerId);
        return CinematicController.INSTANCE.isLayerHidden(category);
    }
}
