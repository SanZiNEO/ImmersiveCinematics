package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterDiagnostics;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实际生成计数（C6）：{@code SpawnState.afterSpawn} 是原版「本次 spawn 成功入账」的唯一入口
 * （全局上限、局部上限、生成位置校验都已通过）。只在 HEAD 计数，不改变刷怪逻辑。
 */
@Mixin(NaturalSpawner.SpawnState.class)
public abstract class NaturalSpawnerSpawnStateMixin {

    @Inject(method = "afterSpawn", at = @At("HEAD"))
    private void immersivecinematics_countSpawned(Mob mob, ChunkAccess chunkAccess, CallbackInfo ci) {
        CameraCenterDiagnostics.onSpawned(mob);
    }
}
