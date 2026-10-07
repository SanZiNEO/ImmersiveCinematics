package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CinematicOcclusion;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /**
     * 每帧开始：按帧统一决定"lane 相机在实心方块里 → 关掉遮挡剔除"（原版旁观者语义）。
     * 见 {@link CinematicOcclusion}——可见区块集合是共享状态，必须整帧一致，否则画面会来回闪。
     * 主画面（原版玩家相机）不在判定输入内：原版 {@code player.isSpectator()} 那条判定对它本就正确。
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderFrameStart(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        CinematicOcclusion.beginFrame(Minecraft.getInstance());
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(Camera camera, float partialTick, boolean useFOVSetting,
                          CallbackInfoReturnable<Double> cir) {
        // lane 相机（多相机渲染底层）：用自己的 fov/zoom。
        // 非 lane 相机（原版主相机）放行原版结果——主相机替换链已退役。
        LaneRenderer.Lane lane = LaneRenderer.laneOf(camera);
        if (lane != null) {
            CameraState laneState = lane.state();
            cir.setReturnValue(ic$effectiveFov(laneState.fov(), laneState.zoom()));
        }
    }

    /** fov/zoom → 生效 FOV。投影矩阵安全保护：FOV 超过约 170° 会导致画面翻转/畸变。 */
    private static double ic$effectiveFov(float fov, float zoom) {
        double effective = fov / zoom;
        if (effective > 170.0) effective = 170.0;
        if (effective < 0.1) effective = 0.1;
        return effective;
    }

    /**
     * 当电影镜头激活时，取消手臂和手持物品的渲染。
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            Boolean setting = CinematicController.INSTANCE.isHideArm();
            if (setting == null ? CinematicController.INSTANCE.isHideHud() : setting) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void onBobHurt(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            Boolean setting = CinematicController.INSTANCE.isSuppressBob();
            if (setting == null ? CinematicController.INSTANCE.isHideHud() : setting) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void onBobView(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            Boolean setting = CinematicController.INSTANCE.isSuppressBob();
            if (setting == null ? CinematicController.INSTANCE.isHideHud() : setting) {
                ci.cancel();
            }
        }
    }

    // ===== 相机 Roll（翻滚角）=====
    // 主相机的 roll 钩子已随主相机替换链退役删除（lane 的 roll 在 LaneRenderer.renderLane 内
    // 施加到该 lane 自己的 PoseStack 上：绕相机视线轴旋转，roll>0 = 屏幕空间顺时针）。
}