package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void onCanAttack(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (CameraManager.INSTANCE.isActive()
                && CinematicController.INSTANCE.isBlockMobAi()
                && target instanceof Player) {
            cir.setReturnValue(false);
        }
    }
}
