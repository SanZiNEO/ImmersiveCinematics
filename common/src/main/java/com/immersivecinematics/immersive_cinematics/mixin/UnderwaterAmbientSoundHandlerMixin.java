package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.UnderwaterAmbientSoundHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 听者=相机时，水下环境音判定使用相机位置。
 */
@Mixin(UnderwaterAmbientSoundHandler.class)
public abstract class UnderwaterAmbientSoundHandlerMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUnderWater()Z"))
    private boolean immersivecinematics_cameraUnderWater(LocalPlayer player) {
        if (AudioListenerController.isCameraListener()) {
            Vec3 pos = AudioListenerController.getListenerPosition();
            return player.level().getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
        }
        return player.isUnderWater();
    }
}
