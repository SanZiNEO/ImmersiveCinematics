package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 听者=相机时，群系环境音采样点重定向到相机。
 * <p>
 * 只处理 {@code tick()} 中直接调用 {@code LocalPlayer.getX/getY/getZ} 的主采样路径，
 * 不进入 lambda 合成方法，避免硬编码 Fabric/Forge 的 lambda 方法名。
 */
@Mixin(BiomeAmbientSoundsHandler.class)
public abstract class BiomeAmbientSoundsHandlerMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private double immersivecinematics_cameraX(LocalPlayer player) {
        if (AudioListenerController.isCameraListener()) return cameraPos().x;
        return player.getX();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    private double immersivecinematics_cameraY(LocalPlayer player) {
        if (AudioListenerController.isCameraListener()) return cameraPos().y;
        return player.getY();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private double immersivecinematics_cameraZ(LocalPlayer player) {
        if (AudioListenerController.isCameraListener()) return cameraPos().z;
        return player.getZ();
    }

    private static Vec3 cameraPos() {
        return AudioListenerController.getListenerPosition();
    }
}
