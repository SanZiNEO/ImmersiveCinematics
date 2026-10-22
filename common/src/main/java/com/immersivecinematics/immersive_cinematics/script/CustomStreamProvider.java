package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.util.concurrent.CompletableFuture;

/**
 * 自定义音频流提供者 — 供 {@code SoundEngineMixin} 识别：原版 SoundEngine 取流时，若实例实现本接口则使用我们的流
 * （文件/资源解码的 PCM），否则走原版 {@code SoundBufferLibrary.getStream}。跨 Forge/Fabric 通用。
 */
public interface CustomStreamProvider {

    /**
     * 返回该声音的 PCM 流。
     *
     * @param soundBuffers 原版缓冲库（本实现不使用，保留原版签名）
     * @param sound        原版解析出的 Sound（本实现只用其元数据，取流走自己的 PCM）
     * @param looping      是否循环（循环流读完后回卷，而不是结束）
     * @return 已就绪的音频流（调用方负责关闭）
     */
    CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping);
}
