package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 相机 Mixin —— <b>lane 相机接管</b>：把 {@link LaneRenderer} 各 lane 的 {@link CameraState} 写进该 lane
 * 自己的 {@link Camera} 实例（每 lane 一个相机实例 + 独立投影，见 {@code client/lane/LaneRenderer}）。
 *
 * <p><b>主相机替换链已退役</b>（plans/0.3.6/parallel-playback.md §3.3）：主画面 = 「dest 全屏、opacity=1」的
 * 全屏 lane 特例，玩家看到的画面 = 合成层输出；原版主相机不再被写入模组相机状态，照常走原版
 * {@code Camera.setup()}（玩家位置）——零 lane 时画面即原版视角。</p>
 *
 * <p>🔊 声音系统兼容：lane 相机用 HEAD + cancel 拦截原版 {@code setup()}，避免中间状态导致声音跳变；
 * 手动设置原版 setup() 中会被初始化的关键字段：</p>
 * <ul>
 *   <li>initialized = true → 声音系统 isInitialized() 检查通过</li>
 *   <li>level → 流体检测（getFluidInCamera）需要</li>
 *   <li>entity → 渲染管线多处调用 getEntity()</li>
 *   <li>detached → isDetached() 控制玩家模型渲染</li>
 * </ul>
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
     * 拦截 {@code Camera.setup()}：lane 相机读该 lane 自己的 {@link CameraState}。
     *
     * <p>使用 HEAD + cancel 而非 RETURN，避免原版 setup() 先设置玩家位置再被覆盖的中间状态——
     * 这种跳变会导致 OpenAL Listener 位置/朝向在玩家和相机之间来回切换，产生声音卡顿噪音。</p>
     *
     * <p>非 lane 相机（原版主相机）直接放行：原版 setup 照常执行。</p>
     */
    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter level, Entity entity, boolean detached,
                         boolean mirror, float partialTick, CallbackInfo ci) {
        // lane 相机（多相机渲染底层）：由 LaneRenderer 的 lane 状态驱动
        LaneRenderer.Lane lane = LaneRenderer.laneOf((Camera) (Object) this);
        if (lane == null) return;

        CameraState laneState = lane.state();
        this.initialized = true;
        this.level = level;
        this.entity = entity;
        this.detached = detached;

        // 直接读取精确值（每帧已由 onRenderFrame 精确重算，不需要 partialTick 插值）
        Vec3 lanePos = laneState.position();
        setPosition(lanePos.x, lanePos.y, lanePos.z);
        setRotation(laneState.yaw(), laneState.pitch());

        ci.cancel();  // 取消原版 setup，使用该 lane 的位置/旋转
    }

    /**
     * lane 相机返回玩家 Entity，避免渲染管线 NPE / 实体层把玩家当作相机本体跳过
     * （lane 的 setup 被 cancel，entity 字段由本 Mixin 手动设置，此拦截作为额外安全措施保留）。
     */
    @Inject(method = "getEntity", at = @At("HEAD"), cancellable = true)
    private void onGetEntity(CallbackInfoReturnable<Entity> cir) {
        if (LaneRenderer.laneOf((Camera) (Object) this) != null) {
            cir.setReturnValue(Minecraft.getInstance().player);
        }
    }

    /**
     * lane 相机返回 true 使 Minecraft 认为相机处于"分离"（第三人称）模式，从而渲染玩家身体模型
     * （isRenderPlayerModel 开关）。
     * <p>
     * 注意：不影响手臂渲染！手臂渲染由 {@code GameRenderer.renderItemInHand()} 控制，该方法检查的是
     * {@code CameraType.isFirstPerson()}；也不影响 HUD（HUD 由 GuiMixin 单独处理）。
     */
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
        if (LaneRenderer.laneOf((Camera) (Object) this) != null) {
            cir.setReturnValue(CinematicController.INSTANCE.isRenderPlayerModel());
        }
    }
}
