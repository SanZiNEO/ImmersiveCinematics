package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import com.immersivecinematics.immersive_cinematics.util.AudioConstants;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 相机听者模式下的客户端衰减放宽。
 *
 * 原版普通音效 {@code attenuation_distance=16}，{@code SoundEngine.play} 按听者到声源距离做剔除 / 线性衰减；
 * 相机听者远离角色时远侧生物音会被 16 格剔除。相机听者激活期间把衰减距离下限抬到
 * {@link AudioConstants#CAMERA_ATTENUATION_DISTANCE}，其余情况原样返回。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Redirect(method = "play", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/sounds/Sound;getAttenuationDistance()I"))
    private int immersivecinematics_cameraAttenuationDistance(Sound sound) {
        int base = sound.getAttenuationDistance();
        if (AudioListenerController.isCameraListener()) {
            return (int) Math.max(base, AudioConstants.CAMERA_ATTENUATION_DISTANCE);
        }
        return base;
    }
}
