package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CinematicOcclusion;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneDebugDriver;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ScriptLaneDriver;
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
     * 每帧开始（帧首，世界渲染之前）两件事，顺序固定：
     * <ol>
     *   <li><b>帧驱动 + lane 注册</b>（{@code renderLevel} 参数为真且已有世界时）：
     *       {@link CameraManager#onRenderFrame()} 推进时钟 / 轨道 / lane 快照，随后注册本帧 lane
     *       （脚本 lane 优先，无脚本时调试驱动照常）。必须排在<b>世界渲染之前</b>：视图中心
     *       （{@code LevelRendererMixin} 改写 {@code setupRender} 的玩家坐标）与遮挡剔除的整帧决策
     *       （{@link CinematicOcclusion}）都读 lane 表，挂在世界渲染之后会让两者都滞后一帧
     *       （主 pass 读到上一帧的 lane 相机位置）。</li>
     *   <li><b>遮挡剔除整帧决策</b>：{@link CinematicOcclusion#beginFrame(Minecraft)}——必须排在 lane
     *       注册之后，读的才是本帧 lane 表。</li>
     * </ol>
     *
     * <p>与原挂点（{@code LaneRendererMixin} 的 {@code GameRenderer.renderLevel} RETURN）等价性：两者都只在
     * "世界渲染这一次调用"里跑，故此处用 {@code renderLevel} 参数 + {@code level != null} 守卫（原版调用点
     * 的条件就是 {@code p_109096_ && this.minecraft.level != null}）——世界未渲染（加载中 / 主菜单）时不驱动、
     * 不注册，时钟不空转（与原挂点一致）。</p>
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderFrameStart(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (renderLevel && mc.level != null) {
            CameraManager mgr = CameraManager.INSTANCE;
            if (mgr.isActive()) {
                mgr.onRenderFrame();
            }
            if (!ScriptLaneDriver.tick(mc)) {
                LaneDebugDriver.tick(mc, partialTick);   // 未开 ICINEMATICS_QUADRANT 时直接返回
            }
        }
        CinematicOcclusion.beginFrame(mc);
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