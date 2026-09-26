package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraVirtualCenterState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 实体配对距离改用相机中心。
 * <p>
 * 相机片段激活时，把原版 {@code ChunkMap$TrackedEntity.updatePlayer} 里的
 * {@code player.position()} 换成相机坐标（x/z，y 固定 0，距离判定只读 x/z），
 * 使相机附近的实体按原版规则配对/取消配对（距离阈值、seenBy、addPairing 全部沿用原版）。
 * 目标类是包私有内部类，只能用字符串 targets。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityCameraMixin {

    @Redirect(
            method = "updatePlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 immersiveCinematics$cameraAwarePosition(ServerPlayer player) {
        Vec3 camera = CameraVirtualCenterState.cameraCenterFor(player);
        return camera != null ? camera : player.position();
    }
}
