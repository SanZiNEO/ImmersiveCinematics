package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
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
 * <p>
 * 🔊 声音系统兼容：
 * - 使用 HEAD + cancel 拦截原版 setup()，避免中间状态导致声音跳变
 * - 手动设置 initialized = true，确保 SoundEngine.updateSource() 的
 *   isInitialized() 检查通过，OpenAL Listener 正确更新到相机坐标
 * - 同时设置 level 和 entity 字段，确保流体检测等子系统正常工作
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    private boolean initialized;

    @Shadow
    private BlockGetter level;

    @Shadow
    private Entity entity;

    @Shadow
    private boolean detached;

    /**
     * 🎬 拦截 Camera.setup()，用电影相机位置/旋转替换原版逻辑
     * <p>
     * 使用 HEAD + cancel 而非 RETURN，避免原版 setup() 先设置玩家位置
     * 再被我们覆盖的中间状态——这种跳变会导致 OpenAL Listener 位置/朝向
     * 在玩家和相机之间来回切换，产生声音卡顿噪音。
     * <p>
     * 手动设置原版 setup() 中会被初始化的关键字段：
     * - initialized = true → 声音系统 isInitialized() 检查通过
     * - level → 流体检测（getFluidInCamera）需要
     * - entity → 渲染管线多处调用 getEntity()
     * - detached → isDetached() 控制玩家模型渲染
     */
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

            // 手动设置原版 setup() 中的关键字段（因为 ci.cancel() 跳过了原版逻辑）
            this.initialized = true;
            this.level = level;
            this.entity = entity;
            this.detached = detached;

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
     * <p>
     * 注意：现在我们在 onSetup 中手动设置了 this.entity，此拦截作为额外安全措施保留
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
     * <p>
     * 注意：现在我们在 onSetup 中手动设置了 this.detached，此拦截作为额外安全措施保留
     */
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue(CinematicController.INSTANCE.isRenderPlayerModel());
        }
    }
}
