package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraVirtualCenterState;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 虚拟相机中心：把原版 {@link ChunkMap} 的玩家差集中心临时替换成相机中心。
 *
 * 复用原版 {@link ChunkMap#move(ServerPlayer)} 的「旧中心 = {@code lastSectionPos}、新中心 =
 * {@code SectionPos.of(player)}」机制，只替换新中心的来源：相机激活 → 原版 move 自动从玩家过渡到相机；
 * 相机移动 → 原版自动做 old/new 差集；相机结束 → 原版自动从相机过渡回玩家。DistanceManager 与客户端发送
 * 全部仍由原版驱动。相机中心状态存在 {@link CameraVirtualCenterState}，供本类与实体配对 mixin 共用。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapCameraMixin {

    /**
     * move / updatePlayerPos 里所有 {@code SectionPos.of(player)} 都换成虚拟相机 section。
     */
    @Redirect(
            method = {"move", "updatePlayerPos"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;of(Lnet/minecraft/world/level/entity/EntityAccess;)Lnet/minecraft/core/SectionPos;")
    )
    private SectionPos immersiveCinematics$redirectSectionPosOf(EntityAccess entityAccess) {
        if (entityAccess instanceof ServerPlayer player) {
            SectionPos camera = CameraVirtualCenterState.cameraSectionFor(player);
            if (camera != null) {
                return camera;
            }
        }
        return SectionPos.of(entityAccess);
    }

    /**
     * move 里的 {@code arg.getBlockX()} 换成虚拟相机中心块坐标。
     * 这样 old/new 循环的“新中心 j2x/k2”也跟随相机。
     */
    @Redirect(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getBlockX()I")
    )
    private int immersiveCinematics$redirectBlockX(ServerPlayer player) {
        SectionPos camera = CameraVirtualCenterState.cameraSectionFor(player);
        if (camera != null) {
            return camera.x() << 4;
        }
        return player.getBlockX();
    }

    @Redirect(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getBlockZ()I")
    )
    private int immersiveCinematics$redirectBlockZ(ServerPlayer player) {
        SectionPos camera = CameraVirtualCenterState.cameraSectionFor(player);
        if (camera != null) {
            return camera.z() << 4;
        }
        return player.getBlockZ();
    }
}
