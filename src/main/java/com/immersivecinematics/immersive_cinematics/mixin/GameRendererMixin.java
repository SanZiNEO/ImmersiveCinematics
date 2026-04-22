package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.handler.CinematicCameraHandler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(CallbackInfoReturnable<Double> cir) {
        CinematicCameraHandler cameraHandler = CinematicCameraHandler.getInstance();
        if (cameraHandler.isActive() && cameraHandler.getCameraEntity() != null) {
            cir.setReturnValue((double) cameraHandler.getCameraEntity().getFov());
        }
    }
}