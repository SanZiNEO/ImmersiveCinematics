package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 相机 Mixin — 拦截 Camera.setup() 实现自定义相机位置和旋转
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - 每渲染帧在 onSetup 中先调 mgr.onRenderFrame() 计算精确位置
 * - 然后直接读取 currentPosition / currentYaw / currentPitch
 * - 不需要 partialTick 插值，因为每帧都已用 System.nanoTime() 精确重算
 * <p>
 * 这与 ReplayMod 的 CameraEntity.setCameraPosition(prevX=x) 思路一致：
 * 当 prev=current 时，MC 自身的 partialTick 插值结果恒等于 current，
 * 等效于直接使用精确值。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter level, Entity entity, boolean detached,
                         boolean mirror, float partialTick, CallbackInfo ci) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            // 🎬 先驱动帧回调：用实时时间精确计算当前帧的相机状态
            mgr.onRenderFrame();

            // ⚠️ onRenderFrame() 可能触发 deactivateNow()（退场动画结束时），
            // 导致 active 变为 false 且所有相机数据被 reset。
            // 此时必须放弃自定义相机，让原版 setup 用玩家正常位置渲染，
            // 否则会读到 reset 后的默认值 (0,0,0)，造成一帧白模闪烁。
            if (!mgr.isActive()) {
                return;  // 不 cancel，让原版 setup 正常执行
            }

            // 直接读取精确值（每帧已由 onRenderFrame 精确重算，不需要 partialTick 插值）
            Vec3 pos = mgr.getPath().getPosition();
            setPosition(pos.x, pos.y, pos.z);

            float yaw = mgr.getProperties().getYaw();
            float pitch = mgr.getProperties().getPitch();

            setRotation(yaw, pitch);

            // Roll 由 Forge 事件 ViewportEvent.ComputeCameraAngles 处理，
            // 在 GameRenderer.renderLevel() 中通过 event.setRoll() 应用到 PoseStack

            ci.cancel();  // 取消原版 setup，使用我们的位置/旋转
        }
    }

    /**
     * 返回玩家 Entity，避免渲染管线 NPE
     * 原版 Camera.setup() 中 this.entity = entity，我们 cancel 了 setup 导致 entity 字段为 null
     * 渲染管线多处调用 getEntity()，返回 null 会崩溃
     */
    @Inject(method = "getEntity", at = @At("HEAD"), cancellable = true)
    private void onGetEntity(CallbackInfoReturnable<Entity> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue(Minecraft.getInstance().player);
        }
    }

    /**
     * 返回 true 使 Minecraft 认为相机处于"分离"（第三人称）模式
     * 效果：
     * 1. 渲染玩家身体模型（第一人称不渲染玩家自己）
     * 2. ⚠️ 不影响手臂渲染！手臂渲染由 GameRenderer.renderItemInHand() 控制，
     *    该方法检查的是 CameraType.isFirstPerson()，而不是 Camera.isDetached()
     * 3. 不影响 HUD，HUD 需要单独处理（GuiMixin）
     */
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue(true);
        }
    }
}
