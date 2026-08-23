package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraAnchorManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让原版刷怪判定认为“相机锚点附近也有刷怪中心”。
 * <p>
 * 纯坐标方案：不创建任何 Player 实体，只让 {@code ChunkMap.anyPlayerCloseEnoughForSpawning}
 * 在相机锚点附近返回 true，从而触发 {@code NaturalSpawner}。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    private ServerLevel level;

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_cameraAnchorCloseForSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (CameraAnchorManager.INSTANCE.isAnyAnchorNear(this.level, chunkPos)) {
            cir.setReturnValue(true);
        }
    }
}
