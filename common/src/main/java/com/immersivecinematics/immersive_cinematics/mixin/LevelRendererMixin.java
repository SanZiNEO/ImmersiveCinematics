package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 渲染视图中心跟随相机（0.3.5 第3轮-B v5）：
 * 1.20.1 的 {@code LevelRenderer.setupRender} 用 {@code minecraft.player} 坐标计算 ViewArea
 * （可见/待建渲染区块）中心——相机飞出玩家渲染距离后，区块即使已加载到客户端缓存也不被构建/渲染。
 * 本 Mixin 在过场激活且非预览时：
 * <ul>
 *   <li>把三个 {@code SectionPos.posToSectionCoord}（i/j/k）改为相机区块坐标</li>
 *   <li>把 {@code ViewArea.repositionCamera} 改为相机坐标</li>
 * </ul>
 * 非过场/预览保持原样（用玩家）。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;posToSectionCoord(D)I", ordinal = 0))
    private int immersivecinematics_cameraSectionX(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? SectionPos.posToSectionCoord(v.x) : SectionPos.posToSectionCoord(coord);
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;posToSectionCoord(D)I", ordinal = 1))
    private int immersivecinematics_cameraSectionY(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? SectionPos.posToSectionCoord(v.y) : SectionPos.posToSectionCoord(coord);
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;posToSectionCoord(D)I", ordinal = 2))
    private int immersivecinematics_cameraSectionZ(double coord) {
        Vec3 v = cinematicViewCenter();
        return v != null ? SectionPos.posToSectionCoord(v.z) : SectionPos.posToSectionCoord(coord);
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;repositionCamera(DD)V"))
    private void immersivecinematics_cameraReposition(ViewArea viewArea, double x, double z) {
        Vec3 v = cinematicViewCenter();
        if (v != null) {
            viewArea.repositionCamera(v.x, v.z);
        } else {
            viewArea.repositionCamera(x, z);
        }
    }

    private static Vec3 cinematicViewCenter() {
        if (!CameraManager.INSTANCE.isActive() || CameraManager.INSTANCE.isPreviewMode()) return null;
        return CameraManager.INSTANCE.getPath() != null ? CameraManager.INSTANCE.getPath().getPosition() : null;
    }
}
