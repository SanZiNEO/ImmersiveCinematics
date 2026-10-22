package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 音频听者：把 {@code SoundEngine.updateSource} 的听者相机换成模组代理。
 *
 * 原版 {@code Minecraft.tick} 传的是主相机（= 原版玩家相机），两种听者模式都得显式给代理：
 * {@code "camera"} → {@link AudioListenerController#cameraListener()}（顶层实例的活跃 clip 相机）；
 * {@code "player"}（默认）→ {@link AudioListenerController#playerCamera()}（玩家视角代理）。
 * 无活跃过场时原样放行（零差异）。
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @ModifyArg(method = "updateSource", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundEngine;updateSource(Lnet/minecraft/client/Camera;)V"), index = 0)
    private Camera immersivecinematics_listenerCamera(Camera camera) {
        if (AudioListenerController.isCameraListener()) {
            return AudioListenerController.cameraListener();
        }
        if (AudioListenerController.shouldOverride()) {
            return AudioListenerController.playerCamera();
        }
        return camera;
    }
}
