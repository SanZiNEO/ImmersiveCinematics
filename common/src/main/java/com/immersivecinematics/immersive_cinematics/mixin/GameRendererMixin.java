package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CinematicOcclusion;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneDebugCapture;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ScriptLaneDriver;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustPass;
import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;
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
     *       （脚本 lane 是 lane 的唯一来源，见 {@link ScriptLaneDriver}）。必须排在<b>世界渲染之前</b>：视图中心
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
                // master 调色的实例集合生命周期 = 本帧的实例循环：先复位（预执行首帧等帧间发布不能决定本帧顺序），
                // 再按列表顺序逐实例发布（插入序 = 实例启动顺序 = 叠加顺序，见 MasterColorAdjust）
                MasterColorAdjust.INSTANCE.beginFrame();
                mgr.onRenderFrame();
            }
            ScriptLaneDriver.tick(mc);
        }
        CinematicOcclusion.beginFrame(mc);
    }

    /**
     * 每帧结束（{@code GameRenderer.render} 的 RETURN，覆盖全部返回路径）：调试捕获的<b>窗口终帧</b>读回
     * （{@link LaneDebugCapture#onFrameEnd}）。
     *
     * <p>此刻本帧画面已全部画完（世界 → lane 渲染与合成 → 原版后处理链 → master 调色 → GUI / 屏幕 / 提示），
     * 主 framebuffer 还留着最终图像：原版 {@code blitToScreen} 在 {@code Minecraft.runTick} 里、本方法返回
     * <b>之后</b>才调用（1.20.1 {@code Minecraft.java:1044-1045}），只是把主画面整幅拷到窗口、不改变主画面内容
     * ——所以这里读回的内容 = 玩家看到的窗口画面。</p>
     *
     * <p>关闭 {@code ICINEMATICS_CAPTURE} / 本帧未捕获 / 未进世界时零差异（内部第一行返回）。</p>
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderFrameEnd(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        LaneDebugCapture.onFrameEnd(Minecraft.getInstance());
    }

    /**
     * 主画面世界渲染 + 原版后处理链（RPOST）之后、GUI 之前：应用本帧的 master 颜色调整
     * （架构图 RADJ 节点，{@link ColorAdjustPass#render}）。
     *
     * <h2>挂点口径（2026-10-08 用户裁决）</h2>
     * master 调色（含未来的整体 LUT 烘焙）是世界画面的<b>最终字</b>，挂点必须满足三条：
     * <ol>
     *   <li><b>在 lane 合成（MCOMP）之后</b>：调色作用在合成输出（主 framebuffer）上——
     *       {@code LaneRenderer.render} 仍在 {@code renderLevel} 的 RETURN，早于本挂点。</li>
     *   <li><b>在原版后处理链之后</b>：受伤红晕 / 水幕 / 旁观者特效等原版屏幕特效属世界画面，应一并风格化；
     *       挂 RPOST 之后即"读到后处理链的输出"。</li>
     *   <li><b>在 GUI 之前</b>：黑边 / 字幕 / 黑白场转场走 OVERLAY 层、在 GUI 阶段绘制且需要精确色值，
     *       不得被调色 / LUT 污染（GUI 不参与调色）。</li>
     * </ol>
     * 同时与 Iris 的色彩空间转换（{@code finalizeGameRendering}，挂在 {@code renderLevel} 的 TAIL）解耦：
     * 两者不再共享同一个返回指令，顺序完全可控（见 plans/0.3.6/iris-oculus-compat.md）。</p>
     *
     * <h2>注入目标</h2>
     * {@code Lnet/minecraft/client/renderer/GameRenderer;render} 内对
     * {@code Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V} 的调用 —— 原版 1.20.1
     * {@code GameRenderer.render} 里它是 {@code postEffect.process(f)} 之后的下一句
     * （{@code this.minecraft.getMainRenderTarget().bindWrite(true)}，反编译 {@code :1100}），
     * 也是 GUI 段（正交投影 + {@code gui.render}，{@code :1103} 起）之前的最后一句。
     *
     * <p><b>为什么不直接锚 {@code Gui.render}</b>：{@code gui.render} 在原版里被
     * {@code if (!options.hideGui || screen != null)} 包着——玩家按 F1 隐藏 HUD 时整段 GUI 不渲染，
     * 锚在那里会让调色随 HUD 显隐而消失。锚 {@code bindWrite(true)} 位于同一 {@code if (renderLevel && level != null)}
     * 分支内、且不受 HUD 显隐影响，与旧挂点（{@code renderLevel} 的 RETURN）的生效条件逐帧等价。</p>
     *
     * <p>默认零差异：本帧无人发布调色（或参数恒等）时 {@link ColorAdjustPass#render} 第一行即返回，
     * 不取着色器、不建中转缓冲、不切任何 GL 状态。</p>
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