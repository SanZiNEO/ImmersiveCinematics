package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版 {@code setupRender} 专属挂点：视图中心 + 遮挡剔除开关。
 *
 * Sodium / Rubidium / Embeddium 会 {@code @Overwrite} 原版 {@code setupRender}，直接注入必然撞车；
 * 检测到它们时由 MixinPlugin 跳过本类，改走它们基于 {@code Camera} / {@code Frustum} 的优化路径——
 * {@code CameraMixin} 已把虚拟相机写进 {@code Camera}，它们的 {@code Viewport} / {@code setupTerrain}
 * 会跟着该相机走。
 *
 * 跳过本类不影响 lane 渲染：清屏 alpha / 天空与内容开关 / 描边屏蔽在 {@link LevelRendererMixin}，
 * 始终应用。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererVanillaMixin {

    // ===== 视图中心（setupRender 的玩家坐标 → 本帧最上层 lane 相机）=====

    @ModifyVariable(method = "setupRender", require = 0, at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"), ordinal = 0)
    private double immersivecinematics_cameraSectionX(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? v.x : coord;
    }

    @ModifyVariable(method = "setupRender", require = 0, at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"), ordinal = 1)
    private double immersivecinematics_cameraSectionY(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? v.y : coord;
    }

    @ModifyVariable(method = "setupRender", require = 0, at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"), ordinal = 2)
    private double immersivecinematics_cameraSectionZ(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? v.z : coord;
    }

    /**
     * 本帧视图中心；{@code null} = 用原版玩家坐标。
     *
     * lane pass 内：目标维度的渲染器必须跟随它自己那条 lane 的相机（{@code activeLaneState}），
     * 否则目标视图的区块网格会按玩家位置搬动。
     * 主 pass：取本帧最上层 lane 的相机，但只在该 lane 不跨维度时生效——跨维度 lane 的画面由
     * 目标渲染器自己的区块网格提供，不能把主画面的视图中心拖到另一个维度（否则主世界区块全部置脏 / 缺块）。
     *
     * lane 表在本帧世界渲染之前就已注册（帧驱动 + lane 注册挂在 {@code GameRenderer.render} 的 HEAD），
     * 所以这里读到的是本帧的相机位置，不会滞后一帧。
     */
    private static Vec3 cinematicViewCenter() {
        if (LaneRenderer.isRenderingLane()) {
            CameraState laneState = LaneRenderer.activeLaneState();
            return laneState != null ? laneState.position() : null;
        }
        LaneRenderer.Lane lane = LaneRenderer.INSTANCE.topLane();
        if (lane == null) {
            return null;
        }
        String dimension = lane.dimension();
        if (dimension != null && !dimension.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (!dimension.equals(DimensionViews.currentDimensionId(mc.level))) {
                // 跨维度 lane：主 pass 用玩家自己的中心
                return null;
            }
        }
        return lane.state().position();
    }

    // ===== 遮挡剔除开关（lane pass 按本 pass 的相机判定）=====

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private boolean immersivecinematics$occlusionToggled;
    @Unique
    private boolean immersivecinematics$occlusionRestore;

    /**
     * 原版 {@code setupRender} 的遮挡剔除开关（{@code minecraft.smartCull}），lane pass 内按本 pass 的相机判定：
     * 相机所在方块实心渲染 → 本 pass 关掉遮挡剪枝（沿用原版旁观者口径：剪枝只对播种相机成立，相机埋在实心
     * 方块里会看不到外面）。主 pass 与非 lane 帧不改写，走原版判定；RETURN 侧无条件还原。
     */
    @Inject(method = "setupRender", at = @At("HEAD"), require = 0)
    private void immersivecinematics_applyCameraOcclusion(Camera camera, Frustum frustum,
                                                          boolean bl, boolean bl2, CallbackInfo ci) {
        if (!immersivecinematics$occlusionOff(camera)) return;
        this.immersivecinematics$occlusionRestore = this.minecraft.smartCull;
        this.minecraft.smartCull = false;
        this.immersivecinematics$occlusionToggled = true;
    }

    private boolean immersivecinematics$occlusionOff(Camera camera) {
        if (!LaneRenderer.isRenderingLane()
                || !LaneRenderer.isPerLaneCullActive((LevelRenderer) (Object) this)) {
            return false;
        }
        ClientLevel level = this.minecraft.level;
        if (level == null) return false;
        BlockPos pos = camera.getBlockPosition();
        return level.getBlockState(pos).isSolidRender(level, pos);
    }

    @Inject(method = "setupRender", at = @At("RETURN"), require = 0)
    private void immersivecinematics_restoreCameraOcclusion(Camera camera, Frustum frustum,
                                                            boolean bl, boolean bl2, CallbackInfo ci) {
        if (this.immersivecinematics$occlusionToggled) {
            this.minecraft.smartCull = this.immersivecinematics$occlusionRestore;
            this.immersivecinematics$occlusionToggled = false;
        }
    }
}
