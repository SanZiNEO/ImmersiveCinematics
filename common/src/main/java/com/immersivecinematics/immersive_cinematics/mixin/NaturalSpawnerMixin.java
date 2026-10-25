package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraAnchorManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 {@code NaturalSpawner} 的「最近玩家」取更近者（真实玩家与相机锚点谁近用谁）。
 *
 * 坑：原版这次查询传 {@code distance = -1.0}（不设上限），只判断「有没有玩家」——只要服务器里有玩家就永远
 * 返回真实玩家，相机离玩家 128 格外时生物不会在相机附近刷出。
 * 不创建世界实体：虚拟玩家只是纯坐标引用，只用于 {@code distanceToSqr}。
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    /** 刷怪尝试计数（诊断）：相机 128 格内每个「分类 × 区块」一次。 */
    @Inject(
            method = "spawnCategoryForChunk(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At("HEAD")
    )
    private static void immersivecinematics_countSpawnAttempt(MobCategory category, ServerLevel level,
                                                              LevelChunk chunk,
                                                              NaturalSpawner.SpawnPredicate predicate,
                                                              NaturalSpawner.AfterSpawnCallback callback,
                                                              CallbackInfo ci) {
        CameraCenterDiagnostics.onSpawnAttempt(level, chunk.getPos());
    }

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
        Player camera = CameraAnchorManager.INSTANCE.getVirtualPlayer(serverLevel, BlockPos.containing(x, y, z));
        if (camera == null) {
            return real;
        }
        if (real == null) {
            return camera;
        }
        return real.distanceToSqr(x, y, z) <= camera.distanceToSqr(x, y, z) ? real : camera;
    }
}
