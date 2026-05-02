package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.KeyboardHandler;
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
        CinematicController ctrl = CinematicController.INSTANCE;
        if (!CameraManager.INSTANCE.isActive()) return;
        if (!ctrl.isBlockKeyboard()) return;

        // 白名单: Esc — 原版暂停（跳过键检测已改用原始 GLFW 状态，不依赖 KeyMapping）
        if (key == GLFW.GLFW_KEY_ESCAPE) return;

        ci.cancel();
    }
}
