package com.immersivecinematics.immersive_cinematics.mixin;

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
 * <p>Sodium / Rubidium / Embeddium 会 {@code @Overwrite} 原版 {@code setupRender}；检测到它们时
 * 由 MixinPlugin 跳过本类，改走它们基于 {@code Camera} / {@code Frustum} 的优化路径——
 * {@code CameraMixin} 已把虚拟相机写进 {@code Camera}，它们的 {@code Viewport} / {@code setupTerrain}
 * 会跟着该相机走（0.3.5 `07e49db` 的原始设计）。</p>
 *
 * <p>跳过本类不影响 lane 渲染：lane 的清屏 alpha / 天空与内容开关 / 描边屏蔽在
 * {@link LevelRendererMixin}，始终应用。</p>
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
     * 本帧最上层 lane 的相机位置；无活跃 lane（= 原版视角）时为 {@code null} = 用原版玩家坐标。
     *
     * <p>lane 表在本帧<b>世界渲染之前</b>就已注册（帧驱动 + lane 注册挂在 {@code GameRenderer.render}
     * 的 HEAD），所以这里读到的是<b>本帧</b>的相机位置。</p>
     */
    private static Vec3 cinematicViewCenter() {
        LaneRenderer.Lane lane = LaneRenderer.INSTANCE.topLane();
        return lane != null ? lane.state().position() : null;
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
     * 原版 {@code setupRender} 的遮挡剔除开关（{@code minecraft.smartCull}），lane pass 内按本 pass 的相机判定。
     *
     * <p>lane pass 且 per-lane 剔除生效 → 该 lane 相机所在方块实心渲染则本 pass 关掉遮挡剪枝；
     * 主 pass 与非 lane 帧不改写，走原版判定。RETURN 侧无条件还原。</p>
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
        if (!LaneRenderer.isRenderingLane() || !LaneRenderer.isPerLaneCullActive(this.minecraft)) {
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
