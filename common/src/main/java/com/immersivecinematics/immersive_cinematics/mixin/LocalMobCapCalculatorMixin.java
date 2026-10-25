package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterDiagnostics;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 局部上限诊断（C6）：{@code canSpawn} 的返回值区分「允许 / 被局部上限拒绝」。
 * 只在 RETURN 观察，不改变判定；归因按跨维度最近的相机锚点（该类拿不到维度，见
 * {@link CameraCenterDiagnostics#onLocalCap}）。
 */
@Mixin(LocalMobCapCalculator.class)
public abstract class LocalMobCapCalculatorMixin {

    @Shadow
    @Final
    private ChunkMap chunkMap;

    @Inject(method = "canSpawn", at = @At("RETURN"))
    private void immersivecinematics_countLocalCap(MobCategory category, ChunkPos chunkPos,
                                                   CallbackInfoReturnable<Boolean> cir) {
        CameraCenterDiagnostics.onLocalCap(cir.getReturnValue(), chunkPos, this.chunkMap);
    }
}
