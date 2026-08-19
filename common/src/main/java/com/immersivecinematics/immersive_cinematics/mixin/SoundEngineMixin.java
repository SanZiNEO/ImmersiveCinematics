package com.immersivecinematics.immersive_cinematics.mixin;

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
 * 自定义音频流接入原版 SoundEngine（0.3.5 第4轮，跨平台方案）。
 * 用 MixinExtras {@link WrapOperation} 包裹原版取流调用：
 * 若播放实例实现了 {@link CustomStreamProvider}，用我们的流；否则走原版。
 * （不能用 @Redirect：Fabric API 的 SoundSystemMixin 已重定向同一调用点，会冲突崩溃。）
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @WrapOperation(method = "play", at = @At(value = "INVOKE",
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
