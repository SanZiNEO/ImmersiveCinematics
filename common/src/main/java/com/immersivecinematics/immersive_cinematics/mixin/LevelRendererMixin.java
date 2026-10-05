package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CinematicOcclusion;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
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
        return CameraManager.INSTANCE.getPath() != null ? CameraManager.INSTANCE.getPath().getPosition() : null;
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
}
