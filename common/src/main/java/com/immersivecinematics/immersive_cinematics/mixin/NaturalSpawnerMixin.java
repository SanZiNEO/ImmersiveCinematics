package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraAnchorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让 {@code NaturalSpawner} 在“附近没有真实玩家”时，用相机锚点的虚拟玩家引用来算距离。
 * <p>
 * 不创建世界实体：虚拟玩家只是一个纯坐标引用，只用于 distanceToSqr。
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @Redirect(
            method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"
            )
    )
    private static Player immersivecinematics_useCameraAnchorForSpawnDistance(
            ServerLevel serverLevel, double x, double y, double z, double distance, boolean creative
    ) {
        Player real = serverLevel.getNearestPlayer(x, y, z, distance, creative);
        if (real != null) {
            return real;
        }
        Player virtual = CameraAnchorManager.INSTANCE.getVirtualPlayer(serverLevel, BlockPos.containing(x, y, z));
        if (virtual != null) {
            return virtual;
        }
        return null;
    }
}
