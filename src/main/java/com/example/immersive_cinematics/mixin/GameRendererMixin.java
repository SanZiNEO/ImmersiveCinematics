package com.example.immersive_cinematics.mixin;

import com.example.immersive_cinematics.handler.CinematicManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(CallbackInfoReturnable<Double> cir) {
        // 检查是否处于电影模式且有运动路径
        CinematicManager cinematicManager = CinematicManager.getInstance();
        if (cinematicManager.isCinematicActive() && cinematicManager.isMovementActive()) {
            // 替换返回值为我们计算的FOV
            double customFOV = cinematicManager.getCurrentFOV();
            cir.setReturnValue(customFOV);
        }
    }

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void onPick(float p_109095_, CallbackInfo ci) {
        // 如果处于电影模式，直接返回，不进行任何射线检测
        if (CinematicManager.getInstance().isCinematicActive()) {
            ci.cancel();
        }
    }
}
