package com.immersivecinematics.immersive_cinematics.fabric.mixin;

import com.immersivecinematics.immersive_cinematics.script.CustomStreamProvider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * Fabric 专属：自定义音频流接入原版 SoundEngine。
 *
 * <p>Fabric 环境下存在其他模组对
 * {@code SoundBufferLibrary.getStream(ResourceLocation, boolean)} 的 {@code @Redirect}，
 * 因此这里继续使用 MixinExtras {@link WrapOperation} 以避免冲突。
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @WrapOperation(method = "play", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> immersivecinematics_customStream(
            SoundBufferLibrary soundBuffers, ResourceLocation path, boolean looping,
            Operation<CompletableFuture<AudioStream>> original, SoundInstance instance) {
        if (instance instanceof CustomStreamProvider) {
            CustomStreamProvider provider = (CustomStreamProvider) instance;
            return provider.getStream(soundBuffers, instance.getSound(), looping);
        }
        return original.call(soundBuffers, path, looping);
    }
}
