package com.example.immersive_cinematics.mixin;

import com.example.immersive_cinematics.handler.CinematicManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.example.immersive_cinematics.ImmersiveCinematics.MC;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    // 确保当使用虚拟摄像机时，原版玩家仍然会被渲染
    @Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
    private void onIsControlledCamera(CallbackInfoReturnable<Boolean> cir) {
        if (CinematicManager.getInstance().isCinematicActive() && this.equals(MC.player)) {
            cir.setReturnValue(true);
        }
    }
}