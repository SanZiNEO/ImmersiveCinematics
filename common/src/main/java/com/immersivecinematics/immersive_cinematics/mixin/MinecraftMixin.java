package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 听者=相机时，环境粒子/方块音采样中心从玩家改为相机。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"), index = 0)
    private int immersivecinematics_cameraAnimateX(int x) {
        if (AudioListenerController.isCameraListener()) return Mth.floor(cameraPos().x);
        return x;
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"), index = 1)
    private int immersivecinematics_cameraAnimateY(int y) {
        if (AudioListenerController.isCameraListener()) return Mth.floor(cameraPos().y);
        return y;
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"), index = 2)
    private int immersivecinematics_cameraAnimateZ(int z) {
        if (AudioListenerController.isCameraListener()) return Mth.floor(cameraPos().z);
        return z;
    }

    private static Vec3 cameraPos() {
        return AudioListenerController.getListenerPosition();
    }
}
