package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraVirtualCenterAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 虚拟相机中心：把原版 ChunkMap 的玩家差集中心临时替换成相机中心。
 * <p>
 * 核心思路是复用原版 {@link ChunkMap#move(net.minecraft.server.level.ServerPlayer)} 的
 * “旧中心 = lastSectionPos，新中心 = SectionPos.of(player)”机制：
 * <ul>
 *   <li>相机激活：设置虚拟 section，原版 move 自动从玩家过渡到相机；</li>
 *   <li>相机移动：更新虚拟 section，原版 move 自动做 old/new 差集；</li>
 *   <li>相机结束：清除虚拟 section，原版 move 自动从相机过渡回玩家。</li>
 * </ul>
 * 我们只替换“新中心”的来源，DistanceManager/客户端发送全部仍由原版驱动。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapCameraMixin implements CameraVirtualCenterAccess {

    @Unique
    private final Map<UUID, SectionPos> immersiveCinematics$cameraSections = new HashMap<>();

    @Override
    public void immersiveCinematics$setCameraSection(UUID playerId, SectionPos section) {
        immersiveCinematics$cameraSections.put(playerId, section);
    }

    @Override
    public void immersiveCinematics$clearCameraSection(UUID playerId) {
        immersiveCinematics$cameraSections.remove(playerId);
    }

    private SectionPos immersiveCinematics$cameraSectionFor(ServerPlayer player) {
        return immersiveCinematics$cameraSections.get(player.getUUID());
    }

    /**
     * move / updatePlayerPos 里所有 {@code SectionPos.of(player)} 都换成虚拟相机 section。
     */
    @Redirect(
            method = {"move", "updatePlayerPos"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;of(Lnet/minecraft/world/level/entity/EntityAccess;)Lnet/minecraft/core/SectionPos;")
    )
    private SectionPos immersiveCinematics$redirectSectionPosOf(EntityAccess entityAccess) {
        if (entityAccess instanceof ServerPlayer player) {
            SectionPos camera = immersiveCinematics$cameraSectionFor(player);
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
        SectionPos camera = immersiveCinematics$cameraSectionFor(player);
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
        SectionPos camera = immersiveCinematics$cameraSectionFor(player);
        if (camera != null) {
            return camera.z() << 4;
        }
        return player.getBlockZ();
    }
}
