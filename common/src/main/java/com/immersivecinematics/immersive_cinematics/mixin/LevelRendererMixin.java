package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CinematicOcclusion;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
 * 渲染视图中心跟随相机（0.3.5 第3轮-B v5）：
 * 1.20.1 的 {@code LevelRenderer.setupRender} 用 {@code minecraft.player} 坐标计算 ViewArea
 * （可见/待建渲染区块）中心——相机飞出玩家渲染距离后，区块即使已加载到客户端缓存也不被构建/渲染。
 * <p>
 * 本 Mixin 在过场激活且非预览时，把 {@code setupRender} 里的玩家坐标局部变量
 * {@code d0/d1/d2} 替换为相机坐标；后续 {@code SectionPos.posToSectionCoord}
 * 与 {@code ViewArea.repositionCamera} 都会自然使用相机坐标。
 * 非过场/预览保持原样（用玩家）。
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

    private static Vec3 cinematicViewCenter() {
        if (!CameraManager.INSTANCE.isActive() || CameraManager.INSTANCE.isPreviewMode()) return null;
        CameraState state = CameraManager.INSTANCE.getCameraState();
        return state != null ? state.position() : null;
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
     * 我们的相机自由穿墙、等价于旁观者，所以套用同一条件——但**按帧统一决定**
     * （见 {@link CinematicOcclusion}）：可见区块集合是共享状态，逐 pass 用不同的值会互相重建，
     * 表现为画面在"塌缩 / 完整"之间来回闪。
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
     * 已被最后一条 lane 覆盖）。有活跃 lane 时屏蔽它；lane 自己在各自的 FBO 内调用
     * （用 {@link LaneRenderer#isRenderingLane()} 区分；主画面的那份由
     * {@link LaneRenderer#render} 在 lane 块开头先落地）。
     */
    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_laneOutlineGuard(CallbackInfo ci) {
        LaneRenderer renderer = LaneRenderer.INSTANCE;
        if (renderer.hasActiveLanes() && !LaneRenderer.isRenderingLane()) {
            ci.cancel();
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
