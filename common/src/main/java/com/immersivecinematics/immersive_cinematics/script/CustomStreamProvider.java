package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.util.concurrent.CompletableFuture;

/**
 * 自定义音频流提供者 — 供 {@code SoundEngineMixin} 识别：
 * 原版 SoundEngine 取流时，若实例实现本接口则使用我们的流（文件/资源解码的 PCM），
 * 否则走原版 {@code SoundBufferLibrary.getStream}。跨 Forge/Fabric 通用。
 */
public interface CustomStreamProvider {
    CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping);
}
