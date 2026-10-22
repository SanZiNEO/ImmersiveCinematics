package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
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
 * lane 相机接管：把 {@link LaneRenderer} 各 lane 的 {@link CameraState} 写进该 lane 自己的
 * {@link Camera} 实例（每 lane 一个相机实例 + 独立投影）。
 *
 * 主画面本身就是「dest 全屏、opacity=1」的 lane，原版主相机不被写入模组状态、照常走原版
 * {@code Camera.setup()}（玩家位置）——无活跃 lane 时画面即原版视角。
 *
 * lane 相机用 HEAD + cancel 拦 {@code setup()}：先跑原版再覆盖会经过「玩家位置 → 相机位置」的中间态，
 * OpenAL Listener 在两处之间来回跳，产生声音卡顿。被跳过的那次 setup 会初始化的字段必须手填：
 * {@code initialized}（声音系统 {@code isInitialized()} 检查）、{@code level}（{@code getFluidInCamera}）、
 * {@code entity}（渲染管线多处 {@code getEntity()}）、{@code detached}（{@code isDetached()} 控制玩家模型）。
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
     * lane 相机读该 lane 自己的 {@link CameraState}；非 lane 相机（原版主相机）放行原版 setup。
     * 跳变 / 字段补全的原因见类 javadoc。
     */
    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter level, Entity entity, boolean detached,
                         boolean mirror, float partialTick, CallbackInfo ci) {
        // lane 相机由 LaneRenderer 的 lane 状态驱动
        LaneRenderer.Lane lane = LaneRenderer.laneOf((Camera) (Object) this);
        if (lane == null) return;

        CameraState laneState = lane.state();
        this.initialized = true;
        this.level = level;
        this.entity = entity;
        this.detached = detached;

        // 每帧已由 onRenderFrame 精确重算，不需要 partialTick 插值
        Vec3 lanePos = laneState.position();
        setPosition(lanePos.x, lanePos.y, lanePos.z);
        setRotation(laneState.yaw(), laneState.pitch());

        ci.cancel();  // 取消原版 setup，改用 lane 的位置 / 旋转
    }

    /**
     * lane 相机返回玩家 Entity：渲染管线多处假定 {@code getEntity()} 非 null，实体层也会据此
     * 跳过「相机本体」；lane 的 setup 被 cancel 后 entity 由本类手动填，这里作为兜底。
     */
    @Inject(method = "getEntity", at = @At("HEAD"), cancellable = true)
    private void onGetEntity(CallbackInfoReturnable<Entity> cir) {
        if (LaneRenderer.laneOf((Camera) (Object) this) != null) {
            cir.setReturnValue(Minecraft.getInstance().player);
        }
    }

    /**
     * lane 相机返回 true 让 Minecraft 认为相机处于「分离」（第三人称）模式，从而渲染玩家身体模型。
     * 不影响手臂（由 {@code GameRenderer.renderItemInHand()} 按 {@code CameraType.isFirstPerson()}
     * 单独判定），也不影响 HUD（见 {@link GuiMixin}）。
     */
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
        if (LaneRenderer.laneOf((Camera) (Object) this) != null) {
            cir.setReturnValue(CinematicController.INSTANCE.isRenderPlayerModel());
        }
    }
}
