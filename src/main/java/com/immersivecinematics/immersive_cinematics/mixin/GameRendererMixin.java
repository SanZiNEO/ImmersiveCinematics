package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(Camera camera, float partialTick, boolean useFOVSetting,
                          CallbackInfoReturnable<Double> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue((double) mgr.getProperties().getFovInterpolated(partialTick));
        }
    }

    /**
     * 当电影镜头激活时，取消手臂和手持物品的渲染
     *
     * 原版 renderItemInHand() 内部通过 CameraType.isFirstPerson() 判断是否渲染手臂，
     * 而非 Camera.isDetached()。因此 CameraMixin.onIsDetached() 返回 true 无法阻止手臂渲染。
     * 必须在此直接拦截 renderItemInHand 方法。
     *
     * 取消此方法同时也会跳过：
     * - ItemInHandRenderer.renderHandsWithItems() — 手臂和手持物品
     * - ScreenEffectRenderer.renderScreenEffect() — 屏幕覆盖效果（水/岩浆边框等）
     * - 手臂渲染 pass 的 bobHurt / bobView 调用
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            ci.cancel();
        }
    }
}
