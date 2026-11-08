package com.immersivecinematics.immersive_cinematics.media.video;

import com.immersivecinematics.immersive_cinematics.script.CustomStreamProvider;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 视频音轨实例：交给原版 SoundEngine 播放，PCM 来自外部 ffmpeg（{@link ProcessPcmStream}）。
 *
 * 与 AUDIO 轨的 {@code CinematicAudioInstance} 互不影响：本类固定 {@link SoundSource#MASTER}、
 * 相对听者、无衰减、音量 1.0，不参与脚本音量 / 淡入 / 空间化。每次 seek / 循环由
 * {@link VideoAudioTrack} 停掉旧实例并新建，因此本实例不可复用、无状态。
 *
 * 线程边界：构造与 {@code SoundManager.play/stop} 均在渲染线程；{@link #getStream} 由原版
 * SoundEngine 的加载线程调用，内部只启动外部进程，不做解码。
 */
public final class VideoAudioInstance extends AbstractTickableSoundInstance implements CustomStreamProvider {

    private final Path path;
    private final double startSeconds;

    /**
     * @param path         视频文件路径（原样传给 ffmpeg）
     * @param startSeconds 起播位置（秒，负数按 0 处理）
     */
    public VideoAudioInstance(Path path, double startSeconds) {
        super(SoundEvent.createVariableRangeEvent(new ResourceLocation(
                        "immersive_cinematics",
                        "video_audio_" + Integer.toHexString(Objects.hash(path, startSeconds)))),
                SoundSource.MASTER, SoundInstance.createUnseededRandom());
        if (path == null) {
            throw new IllegalArgumentException("path == null");
        }
        this.path = path;
        this.startSeconds = Double.isFinite(startSeconds) ? Math.max(0.0, startSeconds) : 0.0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 1.0F;
        this.pitch = 1.0F;
    }

    @Override
    public boolean canPlaySound() {
        return true;
    }

    @Override
    public boolean canStartSilent() {
        // 分类音量为 0 时也允许启动，由原版按分类音量静音，避免静音状态下声音不同步
        return true;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents events = new WeighedSoundEvents(getLocation(), null);
        this.sound = new Sound(getLocation().toString(), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
                Sound.Type.FILE, true, false, 16);
        events.addSound(this.sound);
        return events;
    }

    /**
     * 提供 ffmpeg 解码出的 PCM 流。
     *
     * @param soundBuffers 原版缓冲库（不使用）
     * @param sound        原版 Sound（不使用，流自行提供格式）
     * @param looping      忽略：视频音轨不循环，循环由 {@link VideoAudioTrack} 重建实例实现
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new ProcessPcmStream(path, startSeconds));
    }

    @Override
    public void tick() {
        // 无逐帧逻辑：起播位置在构造时确定，seek / 循环由 VideoAudioTrack 重建实例
    }
}
