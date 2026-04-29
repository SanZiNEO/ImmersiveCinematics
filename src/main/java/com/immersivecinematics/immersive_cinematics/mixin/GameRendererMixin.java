package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.vertex.PoseStack;
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
        if (mgr.isActive()) {
            // 🎬 帧回调驱动模式：直接读取当前值，不需要 partialTick 插值
            float fov = mgr.getProperties().getFov();
            float zoom = mgr.getProperties().getZoom();
            cir.setReturnValue((double) (fov / zoom));
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

    // ===== 视角摇晃屏蔽 =====

    /**
     * 屏蔽受伤摇晃 + 死亡倾斜
     *
     * bobHurt() 在 renderLevel() 中施加到世界渲染 PoseStack：
     * - 受伤时：Z轴旋转（基于 damageTiltStrength 选项）
     * - 死亡时：Z轴从 40° 过渡到 0° 的倾斜效果
     * 这些效果会在电影镜头激活时叠加到我们控制的相机画面上，需要屏蔽。
     */
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void onBobHurt(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            ci.cancel();
        }
    }

    /**
     * 屏蔽跑动/行走摇晃
     *
     * bobView() 在 renderLevel() 中施加到世界渲染 PoseStack：
     * - 上下位移（走路时视角上下摆动）
     * - Z轴旋转（左右摆）
     * - X轴旋转（前后摆）
     * 电影镜头期间这些效果会造成画面抖动，需要屏蔽。
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void onBobView(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraManager.INSTANCE.isActive()) {
            ci.cancel();
        }
    }

    /**
     * 屏蔽反胃/下界传送门旋转扭曲效果
     *
     * 在 renderLevel() 中，反胃和下界传送门共用同一套旋转扭曲代码：
     *   float f1 = Mth.lerp(partialTick, player.oSpinningEffectIntensity, player.spinningEffectIntensity);
     *   if (f1 > 0.0F) {
     *       // 绕 (0, √2/2, √2/2) 轴旋转 + 缩放 + 反向旋转 → 画面波浪扭曲
     *   }
     * 通过 @Redirect 将此 Mth.lerp 调用的返回值替换为 0.0F，
     * 使 f1 = 0，跳过整个旋转扭曲代码块。
     *
     * 两种效果的差异仅在于旋转速度：
     * - 反胃（CONFUSION）：每 tick 旋转 7°
     * - 下界传送门：每 tick 旋转 20°
     * 它们共享 spinningEffectIntensity 字段，因此一次拦截即可同时屏蔽。
     *
     * ordinal = 0：renderLevel() 中第一个 Mth.lerp(FFF) 调用
     */
    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;lerp(FFF)F",
                    ordinal = 0))
    private float redirectSpinningIntensity(float partialTick, float start, float end) {
        if (CameraManager.INSTANCE.isActive()) {
            return 0.0F;
        }
        return Mth.lerp(partialTick, start, end);
    }
}
