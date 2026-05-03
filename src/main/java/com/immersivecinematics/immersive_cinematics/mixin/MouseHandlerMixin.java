package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    private static boolean shouldBlockMouse() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        if (!CameraManager.INSTANCE.isActive()) return false;
        if (!CinematicController.INSTANCE.isBlockMouse()) return false;
        // 暂停中且 pauseWhenGamePaused=true → 放行，让玩家能点击暂停菜单按钮
        if (mc.isPaused() && CinematicController.INSTANCE.isPauseWhenGamePaused()) return false;
        return true;
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(CallbackInfo ci) {
        if (shouldBlockMouse()) ci.cancel();
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long windowPointer, int button, int action,
                               int modifiers, CallbackInfo ci) {
        if (shouldBlockMouse()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long windowPointer, double xOffset, double yOffset,
                          CallbackInfo ci) {
        if (shouldBlockMouse()) ci.cancel();
    }
}
