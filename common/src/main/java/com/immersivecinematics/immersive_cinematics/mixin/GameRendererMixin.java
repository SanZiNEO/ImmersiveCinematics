package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneDebugCapture;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneDiagnostics;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ScriptLaneDriver;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustPass;
import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
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
     * lane pass 内让 {@code getMainCamera()} 返回当前 lane 的相机：Iris / Oculus 相机 uniform、水下判定、
     * Embeddium 云雾、原版天空色 / 粒子等都经该 getter 取相机；lane pass 之外返回原相机。
     * 不改写 {@code GameRenderer.mainCamera}：private final 字段在构造器之外写入抛
     * {@code IllegalAccessError}。
     */
    @Inject(method = "getMainCamera", at = @At("RETURN"), cancellable = true)
    private void immersivecinematics_laneMainCamera(CallbackInfoReturnable<Camera> cir) {
        Camera laneCamera = LaneRenderer.activeLaneCamera();
        if (laneCamera != null) {
            cir.setReturnValue(laneCamera);
        }
    }

    /**
     * 帧首（世界渲染之前）：帧驱动 + lane 注册。{@link CameraManager#onRenderFrame()} 推进时钟 / 轨道 /
     * lane 快照，{@link ScriptLaneDriver} 注册本帧 lane。必须早于世界渲染：视图中心
     * （{@code LevelRendererVanillaMixin} 改写 {@code setupRender} 的玩家坐标）读 lane 表，
     * 晚挂会滞后一帧。{@code renderLevel} 为真且已有世界时才跑，加载中 / 主菜单不驱动、不注册。
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderFrameStart(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        try {
            // F5 诊断会话帧首：manifest 未启用时仅一次静态 null 检查（不取 nanoTime / 不分配 / 不 IO）。
            LaneDiagnostics.onFrameStart(mc, renderLevel && mc.level != null);
            if (renderLevel && mc.level != null) {
                CameraManager mgr = CameraManager.INSTANCE;
                if (mgr.isActive()) {
                    // master 调色的实例集合生命周期 = 本帧的实例循环：先复位（预执行首帧等帧间发布不能决定本帧顺序），
                    // 再按列表顺序逐实例发布（插入序 = 实例启动顺序 = 叠加顺序，见 MasterColorAdjust）
                    MasterColorAdjust.INSTANCE.beginFrame();
                    mgr.onRenderFrame();
                }
                ScriptLaneDriver.tick(mc);
            }
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            int instances = CameraManager.INSTANCE.isActive() ? CameraManager.INSTANCE.instances().size() : 0;
            ErrorLog.logRateLimited("Render", "frame-head", 5000L,
                    "帧首驱动异常（renderLevel=" + renderLevel + ", level=" + (mc.level != null)
                            + ", active=" + CameraManager.INSTANCE.isActive() + ", instances=" + instances
                            + "）：本帧跳过，下一帧重试", e);
        }
    }

    /**
     * 帧尾（{@code render} 的 RETURN，覆盖全部返回路径）：调试捕获的窗口终帧读回（{@link LaneDebugCapture#onFrameEnd}）。
     * 此刻世界 → lane 合成 → 原版后处理 → master 调色 → GUI 已画完，主 framebuffer 仍是最终图像，原版在
     * {@code render} 返回后才整幅拷到窗口且不改内容，故读回 = 玩家看到的窗口画面。未开捕获 / 未进世界时零差异。
     * 诊断计时（{@link LaneDiagnostics#onFrameEnd}）排在本钩子之后，截图帧在统计中整体剔除。
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderFrameEnd(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LaneDebugCapture.onFrameEnd(mc);
        // F5 诊断会话帧尾：必须排在窗口读回之后，使整帧计时包含 RETURN 前的读回（截图帧随后被剔除）。
        LaneDiagnostics.onFrameEnd(mc);
    }

    /**
     * 主画面世界渲染 + 原版后处理之后、GUI 之前应用 master 调色：调色必须作用在合成输出上且不得污染 GUI
     * （黑边 / 字幕 / 转场走 OVERLAY、在 GUI 阶段绘制且需精确色值）。挂点 = {@code render} 内
     * {@code RenderTarget.bindWrite} 调用（后处理之后、GUI 段之前）；不锚 {@code Gui.render}，因为后者被
     * {@code !options.hideGui || screen != null} 包着，F1 隐藏 HUD 时调色会随 GUI 一起消失。
     * 无人发布调色或参数恒等时 {@link ColorAdjustPass#render} 首行返回（零差异）。
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void onWorldPostProcessed(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        ColorAdjustPass.render(Minecraft.getInstance());
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(Camera camera, float partialTick, boolean useFOVSetting,
                          CallbackInfoReturnable<Double> cir) {
        // lane 相机（多相机渲染底层）用自己的 fov/zoom；非 lane 相机放行原版结果。
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
    // lane 的 roll 在 LaneRenderer.renderLane 内施加到该 lane 自己的 PoseStack 上：
    // 绕相机视线轴旋转，roll>0 = 屏幕空间顺时针。
}