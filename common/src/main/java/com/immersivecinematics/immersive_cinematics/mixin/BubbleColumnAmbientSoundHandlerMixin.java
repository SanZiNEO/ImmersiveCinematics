package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.resources.sounds.BubbleColumnAmbientSoundHandler;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 听者=相机时，气泡柱环境音判定使用相机包围盒。
 */
@Mixin(BubbleColumnAmbientSoundHandler.class)
public abstract class BubbleColumnAmbientSoundHandlerMixin {

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockStatesIfLoaded(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"), index = 0)
    private AABB immersivecinematics_cameraBoundingBox(AABB box) {
        if (AudioListenerController.isCameraListener()) {
            Vec3 pos = AudioListenerController.getListenerPosition();
            return new AABB(pos, pos);
        }
        return box;
    }
}
