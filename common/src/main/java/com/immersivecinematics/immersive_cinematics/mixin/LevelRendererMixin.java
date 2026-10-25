package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ViewScope;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code LevelRenderer} 的通用渲染挂点（不依赖 {@code setupRender}）：整屏描边屏蔽，以及 lane pass 的
 * 清屏 alpha / 内容开关（天空、云、天气、实体）。
 *
 * {@code setupRender} 专属的视图中心与遮挡开关在 {@link LevelRendererVanillaMixin}：优化模组
 * （Sodium / Rubidium / Embeddium）改写该方法时由 MixinPlugin 跳过那个 mixin，改走它们的
 * Camera / Frustum 优化路径；本类的挂点始终应用。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    /** 该 pass 的剔除视锥（{@code prepareCullFrustum} 写入；渲染 pass 诊断采样用）。 */
    @Shadow
    private Frustum cullingFrustum;

    // ===== lane 自包含：屏蔽原版在合成之后那次整屏描边 =====

    /**
     * 原版在 {@code GameRenderer.renderLevel} 返回后整屏贴一次描边，会盖在 lane 合成图外（共享的
     * {@code entityTarget} 已被最后一条 lane 覆盖）：有活跃 lane 时屏蔽这一次，但一帧内另两次必须放行——
     * 主画面自己那次（{@link LaneRenderer#isMainOutlinePass()}，否则主画面发光描边整帧丢失）与
     * lane 自己在各 FBO 内贴的那次（{@link LaneRenderer#isRenderingLane()}）。
     */
    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneOutlineGuard(CallbackInfo ci) {
        LaneRenderer renderer = LaneRenderer.INSTANCE;
        if (renderer.hasActiveLanes() && !LaneRenderer.isRenderingLane()
                && !LaneRenderer.isMainOutlinePass()) {
            ci.cancel();
        }
    }

    // ===== 视角作用域：主视角在 renderLevel HEAD/RETURN；lane 自己的 renderLevel 由 LaneRenderer 包 =====

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void immersivecinematics_viewHead(CallbackInfo ci) {
        ViewScope.onLevelHead();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void immersivecinematics_viewReturn(CallbackInfo ci) {
        ViewScope.onLevelReturn();
    }

    /**
     * 渲染 pass 诊断采样：每个 {@code renderLevel} 返回点各触发一次（主 pass + 每个 lane pass）。
     * 视锥 = 本 pass 的 {@code cullingFrustum}（lane pass 由 {@code prepareCullFrustum} 在渲染前写入）；
     * 只采样主相机与 lane pass，嵌套 pass 由探针自行跳过。debugLogging 关闭时探针立即返回。
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void immersivecinematics_probeReturn(PoseStack poseStack, float partialTick, long nanoTime,
                                                 boolean renderBlockOutline, Camera camera,
                                                 GameRenderer gameRenderer, LightTexture lightTexture,
                                                 Matrix4f projectionMatrix, CallbackInfo ci) {
        CameraManager.INSTANCE.onRenderPass((LevelRenderer) (Object) this, this.cullingFrustum, camera);
    }

    // ===== lane pass 清屏不透明（未覆盖区 = 实心雾色）=====

    /**
     * lane pass 的清屏 alpha 抬到 1：原版清屏 alpha=0，而 lane FBO 的 alpha 不参与合成（合成走
     * {@code ic_lane_blit}，只读 RGB、不透明度由合成层 opacity 决定），抬到 1 只为让「未覆盖区 = 实心雾色」
     * 在 FBO 数据里也成立（调试读回 / 任何读 lane alpha 的消费方），成本为零。RGB 取
     * {@code FogRenderer.levelFogColor()} 刚设好的雾色（与清屏色同源）；非 lane pass 原样放行（零差异）。
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
            shift = At.Shift.BEFORE))
    private void immersivecinematics_laneClearAlpha(PoseStack poseStack, float partialTick, long nanoTime,
                                                    boolean renderBlockOutline, Camera camera,
                                                    GameRenderer gameRenderer, LightTexture lightTexture,
                                                    Matrix4f projectionMatrix, CallbackInfo ci) {
        if (LaneRenderer.isRenderingLane()) {
            float[] fog = RenderSystem.getShaderFogColor();
            RenderSystem.clearColor(fog[0], fog[1], fog[2], 1.0F);
        }
    }

    // ===== lane 内容开关：按 lane 设置关掉天空 / 云 / 天气 / 实体，让该 lane 的 FBO 内容自包含 =====
    // 非 lane pass 恒放行（主画面不受影响）。

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneSkySwitch(PoseStack poseStack, Matrix4f matrix4f, float partialTick,
                                                   Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
        if (!LaneRenderer.shouldRenderSky()) {
            ci.cancel();
        }
    }

    /** 云跟随天空开关（同属天空内容）。 */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneCloudsSwitch(PoseStack poseStack, Matrix4f matrix4f, float partialTick,
                                                      double d, double e, double f, CallbackInfo ci) {
        if (!LaneRenderer.shouldRenderSky()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneWeatherSwitch(LightTexture lightTexture, float partialTick,
                                                       double d, double e, double f, CallbackInfo ci) {
        if (!LaneRenderer.shouldRenderWeather()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneEntitySwitch(Entity entity, double d, double e, double f, float partialTick,
                                                      PoseStack poseStack, MultiBufferSource multiBufferSource,
                                                      CallbackInfo ci) {
        if (!LaneRenderer.shouldRenderEntities()) {
            ci.cancel();
        }
    }
}
