package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CinematicOcclusion;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染视图中心跟随相机（0.3.5 第3轮-B v5；0.3.6 主相机替换链退役后收窄为 <b>lane 专用</b>）：
 * 1.20.1 的 {@code LevelRenderer.setupRender} 用 {@code minecraft.player} 坐标计算 ViewArea
 * （可见/待建渲染区块）中心——相机飞出玩家渲染距离后，区块即使已加载到客户端缓存也不被构建/渲染。
 * <p>
 * 本 Mixin 把 {@code setupRender} 里的玩家坐标局部变量 {@code d0/d1/d2} 替换为<b>正在渲染的
 * lane 自己的</b>相机坐标（{@link LaneRenderer#currentLane()}）；后续
 * {@code SectionPos.posToSectionCoord} 与 {@code ViewArea.repositionCamera} 都会自然使用该坐标。
 * 非 lane pass（原版主画面）返回 {@code null}，保持原版玩家坐标——主相机替换链已退役
 * （见 plans/0.3.6/parallel-playback.md §3.3）。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @ModifyVariable(method = "setupRender", at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"), ordinal = 0)
    private double immersivecinematics_cameraSectionX(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? v.x : coord;
    }

    @ModifyVariable(method = "setupRender", at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"), ordinal = 1)
    private double immersivecinematics_cameraSectionY(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? v.y : coord;
    }

    @ModifyVariable(method = "setupRender", at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"), ordinal = 2)
    private double immersivecinematics_cameraSectionZ(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? v.z : coord;
    }

    /** 正在渲染的 lane 的相机位置；lane pass 之外（主画面）为 {@code null} = 用原版玩家坐标。 */
    private static Vec3 cinematicViewCenter() {
        LaneRenderer.Lane lane = LaneRenderer.currentLane();
        return lane != null ? lane.state().position() : null;
    }

    // ===== 相机在实心方块里：照搬原版旁观者那条逻辑（按帧统一决定）=====

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private boolean immersivecinematics$occlusionToggled;
    @Unique
    private boolean immersivecinematics$occlusionRestore;

    /**
     * 原版 {@code setupRender}：
     * <pre>
     * boolean bl3 = this.minecraft.smartCull;
     * if (player.isSpectator() && level.getBlockState(camera.getBlockPosition()).isSolidRender(...)) {
     *     bl3 = false;   // 旁观者在实心方块里 → 关掉遮挡剔除
     * }
     * </pre>
     * lane 相机自由穿墙、等价于旁观者，所以套用同一条件——但**按帧统一决定**
     * （见 {@link CinematicOcclusion}）：可见区块集合是共享状态，逐 pass 用不同的值会互相重建，
     * 表现为画面在"塌缩 / 完整"之间来回闪。原版主画面由原版自己的旁观者判定处理（相机即玩家相机），
     * 不再由本模组改写。
     */
    @Inject(method = "setupRender", at = @At("HEAD"))
    private void immersivecinematics_applyCameraOcclusion(Camera camera, Frustum frustum,
                                                          boolean bl, boolean bl2, CallbackInfo ci) {
        if (!CinematicOcclusion.isOcclusionOffThisFrame()) return;
        this.immersivecinematics$occlusionRestore = this.minecraft.smartCull;
        this.minecraft.smartCull = false;
        this.immersivecinematics$occlusionToggled = true;
    }

    @Inject(method = "setupRender", at = @At("RETURN"))
    private void immersivecinematics_restoreCameraOcclusion(Camera camera, Frustum frustum,
                                                            boolean bl, boolean bl2, CallbackInfo ci) {
        if (this.immersivecinematics$occlusionToggled) {
            this.minecraft.smartCull = this.immersivecinematics$occlusionRestore;
            this.immersivecinematics$occlusionToggled = false;
        }
    }

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

    // ===== lane pass 清屏不透明（未覆盖区 = 实心雾色）=====

    /**
     * lane pass 的清屏 alpha 抬到 1：{@code LevelRenderer.renderLevel} 开头那次
     * {@code RenderSystem.clear(16640)} 用的是雾色清屏，而 {@code FogRenderer.setupColor} 末尾是
     * {@code clearColor(fogR, fogG, fogB, 0.0f)}（alpha=0）。主画面里 alpha 无所谓（整屏 blit 不看
     * alpha），但 lane 的离屏画面要经合成层的 {@code position_tex}（{@code color.a == 0.0 → discard}
     * + srcalpha 混合）：未覆盖区 alpha=0 会整片透出主画面（雾与地形交接的过渡带被"抠掉"）。
     *
     * <p>RGB 取刚由 {@code FogRenderer.levelFogColor()} 设好的雾色（与清屏色同源，就在本调用前一行），
     * 只把 alpha 抬到 1 → 未覆盖区 = 实心雾色。非 lane pass 原样放行（零差异）。</p>
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
    // 非 lane pass 恒放行（主画面不受影响）；Sodium / Embeddium 下本 Mixin 被插件跳过，
    // 而那时 lane 渲染本身也被 LaneRenderer 禁用，故这些开关不会缺位造成影响。

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
