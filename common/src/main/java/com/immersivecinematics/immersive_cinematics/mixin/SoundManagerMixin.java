package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 音频听者：脚本 listener=player 时把原版 SoundEngine 的 listener 覆盖为玩家视角代理
 * （camera 模式不动，CameraMixin 已让原版用电影相机）。
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @ModifyArg(method = "updateSource", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundEngine;updateSource(Lnet/minecraft/client/Camera;)V"), index = 0)
    private Camera immersivecinematics_playerListener(Camera camera) {
        if (AudioListenerController.shouldOverride()) {
            return AudioListenerController.playerCamera();
        }
        return camera;
    }
}
