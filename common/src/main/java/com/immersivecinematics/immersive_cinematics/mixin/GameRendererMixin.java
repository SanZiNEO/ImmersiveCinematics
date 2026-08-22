package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
            double effective = fov / zoom;
            // 投影矩阵安全保护：FOV 超过约 170° 会导致画面翻转/畸变
            if (effective > 170.0) effective = 170.0;
            if (effective < 0.1) effective = 0.1;
            cir.setReturnValue(effective);
        }
    }

    /**
     * 当电影镜头激活时，取消手臂和手持物品的渲染。
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip()) {
            Boolean setting = CinematicController.INSTANCE.isHideArm();
            if (setting == null ? CinematicController.INSTANCE.isHideHud() : setting) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void onBobHurt(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip()) {
            Boolean setting = CinematicController.INSTANCE.isSuppressBob();
            if (setting == null ? CinematicController.INSTANCE.isHideHud() : setting) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void onBobView(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip()) {
            Boolean setting = CinematicController.INSTANCE.isSuppressBob();
            if (setting == null ? CinematicController.INSTANCE.isHideHud() : setting) {
                ci.cancel();
            }
        }
    }

    // ===== 相机 Roll（翻滚角）=====

    /**
     * 在相机朝向（yaw/pitch）应用到 PoseStack 之后、世界渲染之前，施加 Roll 旋转。
     * <p>
     * 原版 1.20.1 的视图矩阵由 getXRot()/getYRot() 标量绕世界轴构造，
     * 没有 roll 通道（Camera.rotation() 四元数不参与视图矩阵）。
     * 因此必须绕<b>相机视线轴</b>（getLookVector()，含 yaw/pitch 的世界前向）
     * 旋转 roll——绕世界 Z 轴会在不同朝向下漂移（朝东变俯仰、朝北变逆时针）。
     * 此写法在任何朝向下 roll>0 均为屏幕空间顺时针（画面向右倒）。
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.BEFORE))
    private void onBeforePrepareCullFrustum(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive() && mgr.hasActiveCameraClip()) {
            float rollDeg = mgr.getProperties().getRoll();
            if (rollDeg != 0.0F) {
                Vector3f look = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
                // 绕视线轴正角度 = 从观众视角逆时针，取反使 roll>0 为屏幕顺时针（画面向右倒）
                poseStack.mulPose(Axis.of(look).rotationDegrees(-rollDeg));
            }
        }
    }
}