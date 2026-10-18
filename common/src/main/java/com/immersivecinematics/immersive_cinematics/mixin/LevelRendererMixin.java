package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ViewScope;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code LevelRenderer} 的通用渲染挂点（不依赖 {@code setupRender}）：
 *
 * <ul>
 *   <li><b>整屏描边屏蔽</b>：原版在 lane 合成之后那次 {@code doEntityOutline} 会盖在合成图外面。</li>
 *   <li><b>lane pass 清屏 alpha / 内容开关</b>（天空、云、天气、实体）。</li>
 * </ul>
 *
 * <p>{@code setupRender} 专属的视图中心与遮挡开关在 {@link LevelRendererVanillaMixin}：优化模组
 * （Sodium / Rubidium / Embeddium）改写该方法时由 MixinPlugin 跳过那个 mixin，改走它们的
 * Camera / Frustum 优化路径；本类的挂点始终应用。</p>
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // ===== lane 自包含：屏蔽原版在合成之后那次整屏描边 =====

    /**
     * 多相机渲染底层：原版在 {@code GameRenderer.renderLevel} 返回之后会整屏贴一次描边
     * （{@code doEntityOutline()}，1:1 整屏）——那一步会盖在 lane 合成图外面（共享的 {@code entityTarget}
     * 已被最后一条 lane 覆盖）。有活跃 lane 时屏蔽它。
     *
     * <p>一帧里三次调用要分开对待：</p>
     * <ol>
     *   <li><b>主画面自己那次</b>（{@link LaneRenderer#render} 在 lane 块开头发起，此时
     *       {@code entityTarget} 里还是主画面的描边数据）——<b>放行</b>，否则主画面的发光描边整帧丢失
     *       （判定见 {@link LaneRenderer#isMainOutlinePass()}）。</li>
     *   <li><b>lane 自己那次</b>（各 lane 在<b>自己的</b> FBO 内贴描边）——放行，
     *       用 {@link LaneRenderer#isRenderingLane()} 区分。</li>
     *   <li><b>渲染之后原版那次</b>（{@code GameRenderer.render} 里的整屏调用）——屏蔽，
     *       它面对的 {@code entityTarget} 已被最后一条 lane 覆盖。</li>
     * </ol>
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

    // ===== lane pass 清屏不透明（未覆盖区 = 实心雾色）=====

    /**
     * lane pass 的清屏 alpha 抬到 1（<b>性能 / 数据整洁，不承担合成正确性</b>）：
     * {@code LevelRenderer.renderLevel} 开头那次 {@code RenderSystem.clear(16640)} 用的是雾色清屏，而
     * {@code FogRenderer.setupColor} 末尾是 {@code clearColor(fogR, fogG, fogB, 0.0f)}（alpha=0）。
     *
     * <p>合成层的 alpha 契约：lane FBO 的 alpha <b>不参与合成</b>——{@code LaneCompositor} 用专用
     * {@code ic_lane_blit}（{@code vec4(rgb, 1.0) * ColorModulator}）上屏，只读 RGB，不透明度只由合成层
     * 的 opacity 决定。所以这里抬 alpha 不再是正确性要求（旧实现走原版 {@code position_tex} 时才是：
     * {@code color.a == 0.0 → discard} + srcalpha 混合，未覆盖区 alpha=0 会整片透出主画面）。</p>
     *
     * <p>保留的理由：让"未覆盖区 = 实心雾色"这一语义在 FBO 数据里也成立（调试读回的 lane RGBA、
     * 以及将来任何读 lane 纹理 alpha 的消费方），且清屏颜色本来就要设、成本为零。
     * RGB 取刚由 {@code FogRenderer.levelFogColor()} 设好的雾色（与清屏色同源，就在本调用前一行）。
     * 非 lane pass 原样放行（零差异）。</p>
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

    // ===== lane 内容开关（见 plans/0.3.6/render-routes.md §2）=====
    // 非 lane pass 恒放行（主画面不受影响）。

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneSkySwitch(PoseStack poseStack, Matrix4f matrix4f, float partialTick,
                                                   Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
        if (!LaneRenderer.shouldRenderSky()) {
            ci.cancel();
        }
    }

    /** 云跟随"天空"开关（同属天空内容）。 */
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
