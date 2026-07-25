package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(Camera camera, float partialTick, boolean useFOVSetting,
                          CallbackInfoReturnable<Double> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive() && mgr.hasActiveCameraClip()) {
            float fov = mgr.getProperties().getFov();
            float zoom = mgr.getProperties().getZoom();
            cir.setReturnValue((double) (fov / zoom));
        }
    }

    /**
     * 当电影镜头激活时，取消手臂和手持物品的渲染。
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isHideArm()) {
            ci.cancel();
        }
    }

    // ===== 视角摇晃屏蔽 =====

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void onBobHurt(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isSuppressBob()) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void onBobView(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isSuppressBob()) {
            ci.cancel();
        }
    }

    /**
     * 屏蔽反胃/下界传送门旋转扭曲效果。
     */
    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;lerp(FFF)F",
                    ordinal = 0))
    private float redirectSpinningIntensity(float partialTick, float start, float end) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isSuppressBob()) {
            return 0.0F;
        }
        return Mth.lerp(partialTick, start, end);
    }

    // ===== 相机 Roll（翻滚角）=====

    /**
     * 在相机朝向（yaw/pitch）应用到 PoseStack 之后、世界渲染之前，施加 Roll 旋转。
     * <p>
     * 注入点 {@code LevelRenderer.prepareCullFrustum} 之前。
     * 此时 {@code poseStack} 已完成 pitch 和 yaw 旋转，再添加 roll 不影响其他渲染环节。
     * 等价于旧 Forge {@code ViewportEvent.ComputeCameraAngles} 的行为。
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.BEFORE))
    private void onBeforePrepareCullFrustum(PoseStack poseStack, CallbackInfo ci) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive() && mgr.hasActiveCameraClip()) {
            float rollDeg = mgr.getProperties().getRoll();
            if (rollDeg != 0.0F) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rollDeg));
            }
        }
    }
}
