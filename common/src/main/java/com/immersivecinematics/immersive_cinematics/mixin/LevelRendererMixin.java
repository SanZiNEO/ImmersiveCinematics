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
 * 渲染视图中心跟随相机（0.3.5 第3轮-B v5；0.3.6 主相机替换链退役后改为 <b>lane 驱动</b>）：
 * 1.20.1 的 {@code LevelRenderer.setupRender} 用 {@code minecraft.player} 坐标计算 ViewArea
 * （可见/待建渲染区块）中心——相机飞出玩家渲染距离后，区块即使已加载到客户端缓存也不被构建/渲染。
 * <p>
 * 本 Mixin 把 {@code setupRender} 里的玩家坐标局部变量 {@code d0/d1/d2} 替换为<b>本帧最上层 lane</b>
 * 的相机坐标（{@link LaneRenderer#topLane()}）；后续 {@code SectionPos.posToSectionCoord} 与
 * {@code ViewArea.repositionCamera} 都会自然使用该坐标。无活跃 lane 时返回 {@code null} =
 * 原版玩家坐标（主画面替换链已退役：原版主相机就是玩家相机，见 plans/0.3.6/parallel-playback.md §3.3）。
 * <p>
 * <b>为什么是"最上层 lane"而不是"每条 lane 各自的相机"</b>：可见区块网格是单份共享状态，整帧只能有一个
 * 中心——逐 pass 换中心会让 {@code ViewArea.repositionCamera} 在每个 pass 把整张网格搬走并让区块全部
 * 置脏重建（{@code RenderChunk.setOrigin → reset() → dirty}），一帧内反复来回搬 = 持续重建、画面缺块。
 * 逐 lane 独立可见集合是长期方向（见 {@code multi-camera-rendering.md}）。
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

    /**
     * 本帧最上层 lane 的相机位置；无活跃 lane（= 原版视角）时为 {@code null} = 用原版玩家坐标。
     *
     * <p>lane 表在本帧<b>世界渲染之前</b>就已注册（帧驱动 + lane 注册挂在 {@code GameRenderer.render}
     * 的 HEAD，见 {@code GameRendererMixin.onRenderFrameStart}），所以这里读到的是<b>本帧</b>的相机位置，
     * 不存在"上一帧 lane 表"的滞后。</p>
     */
    private static Vec3 cinematicViewCenter() {
        LaneRenderer.Lane lane = LaneRenderer.INSTANCE.topLane();
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
     * 原版 {@code setupRender} 里的遮挡剔除开关：
     * <pre>
     * boolean bl3 = this.minecraft.smartCull;
     * if (player.isSpectator() && level.getBlockState(camera.getBlockPosition()).isSolidRender(...)) {
     *     bl3 = false;   // 旁观者在实心方块里 → 关掉遮挡剔除
     * }
     * </pre>
     * 本模组在<b>有活跃 lane 的帧整帧把它压成 false</b>（判定见 {@link CinematicOcclusion}）：
     * 可见区块集合是共享状态、且由某个 pass 的相机经 BFS 遮挡剪枝重建，剪枝只对<b>播种相机</b>成立 ⇒
     * 其它机位（各 lane）会整块缺 16 格区块（副画面里的倾斜裁切矩形）。关掉遮挡剪枝后 BFS 退化为
     * "视距内全部区块"，各 pass 仍各自 {@code applyFrustum} 视锥过滤。
     * <p>必须**整帧统一**（各 pass 用不同的值会互相重建，表现为画面在"塌缩 / 完整"之间来回闪）。
     * 原版对玩家相机自己的旁观者判定不受影响：本模组只在 lane 活跃的帧改写这个字段，非 lane 帧原样放行。</p>
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
