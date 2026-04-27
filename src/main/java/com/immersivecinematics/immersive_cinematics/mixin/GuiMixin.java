package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 隐藏 HUD 渲染（物品栏、准星、生命值等）
 * 当 CameraManager 处于激活状态时，取消所有 HUD 元素渲染
 */
@Mixin(Gui.class)
public class GuiMixin {

    /**
     * render — 渲染所有 HUD 元素的总入口方法
     * 取消此方法即可隐藏所有 HUD（准星、物品栏、生命值、经验条等）
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            ci.cancel();
        }
    }
}
