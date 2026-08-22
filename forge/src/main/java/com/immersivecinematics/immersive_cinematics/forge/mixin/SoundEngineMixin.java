package com.immersivecinematics.immersive_cinematics.forge.mixin;

import com.immersivecinematics.immersive_cinematics.script.CustomStreamProvider;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * Forge 专属：自定义音频流接入原版 SoundEngine。
 *
 * <p>Forge 的 {@code SoundEngine.play()} 会调用
 * {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)}（Forge 加的 default 方法）。
 * 这里用原版 {@link Redirect} 拦截，不依赖 MixinExtras。
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Redirect(method = "play", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> immersivecinematics_customStream(
            SoundInstance instance, SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        if (instance instanceof CustomStreamProvider) {
            CustomStreamProvider provider = (CustomStreamProvider) instance;
            return provider.getStream(soundBuffers, sound, looping);
        }
        return instance.getStream(soundBuffers, sound, looping);
    }
}
