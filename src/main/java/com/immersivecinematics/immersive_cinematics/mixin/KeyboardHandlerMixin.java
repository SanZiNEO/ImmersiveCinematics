package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long windowPointer, int key, int scanCode,
                            int action, int modifiers, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        CinematicController ctrl = CinematicController.INSTANCE;
        if (!CameraManager.INSTANCE.isActive()) return;
        if (!ctrl.isBlockKeyboard()) return;

        // 暂停中且 pauseWhenGamePaused=true → 放行所有按键（让玩家能操作暂停菜单）
        if (mc.isPaused() && ctrl.isPauseWhenGamePaused()) return;

        // 白名单: Esc — 原版暂停
        if (key == GLFW.GLFW_KEY_ESCAPE) return;

        ci.cancel();
    }
}
