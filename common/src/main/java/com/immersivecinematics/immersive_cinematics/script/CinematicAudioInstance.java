package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET;

/**
 * 单声源 OpenAL 音频实例 — 管理一个 OGG 音频的加载、播放、空间定位。
 * <p>
 * 通过 LWJGL 自带的 STBVorbis 解码 OGG，再通过 OpenAL 播放。
 * 支持两种来源：本地文件（video/ 目录）和 Minecraft 资源包。
 */
public class CinematicAudioInstance {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    private int source = 0;
    private int buffer = 0;
    private boolean valid = false;
    private float duration = 0f;

    // Fade state
    private final boolean loop;
    private final float pitch;
    private float currentVolume = 1.0f;
    public CinematicAudioInstance(String soundPath, String sourceType, boolean loop, float pitch) {
        this.loop = loop;
        this.pitch = pitch;

        ShortBuffer rawAudio = null;
        int channels = 0;
        int sampleRate = 0;

        try {
            if ("minecraft".equals(sourceType)) {
                // Load from Minecraft resource pack
                ResourceLocation loc = new ResourceLocation(soundPath);
                Resource resource = Minecraft.getInstance().getResourceManager().getResource(loc).orElse(null);
                if (resource == null) {
                    LOGGER.error("Minecraft sound resource not found: {}", soundPath);
                    return;
                }
                try (InputStream is = resource.open()) {
                    byte[] bytes = readAllBytes(is);
                    ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
                    buf.put(bytes).flip();

                    IntBuffer error = BufferUtils.createIntBuffer(1);
                    long handle = STBVorbis.stb_vorbis_open_memory(buf, error, null);
                    if (handle == 0) {
                        MemoryUtil.memFree(buf);
                        LOGGER.error("Failed to decode OGG from memory for: {}", soundPath);
                        return;
                    }

                    STBVorbisInfo info = STBVorbisInfo.malloc();
                    STBVorbis.stb_vorbis_get_info(handle, info);
                    channels = info.channels();
                    sampleRate = info.sample_rate();
                    int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                    this.duration = (float) totalSamples / (float) sampleRate / (float) channels;

                    rawAudio = MemoryUtil.memAllocShort(totalSamples * channels);
                    STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, rawAudio);
                    rawAudio.flip();

                    info.free();
                    STBVorbis.stb_vorbis_close(handle);
                    MemoryUtil.memFree(buf);
                }
            } else {
                // Load from video/ directory
                Path oggPath = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("immersive_cinematics")
                        .resolve("video")
                        .resolve(soundPath);

                if (!Files.exists(oggPath)) {
                    LOGGER.error("OGG file not found: {}", oggPath);
                    return;
                }

                IntBuffer chBuf = BufferUtils.createIntBuffer(1);
                IntBuffer srBuf = BufferUtils.createIntBuffer(1);
                rawAudio = STBVorbis.stb_vorbis_decode_filename(oggPath.toString(), chBuf, srBuf);
                if (rawAudio == null) {
                    LOGGER.error("Failed to decode OGG file: {}", oggPath);
                    return;
                }

                channels = chBuf.get(0);
                sampleRate = srBuf.get(0);
                int totalSamples = rawAudio.remaining();
                this.duration = (float) totalSamples / (float) sampleRate / (float) channels;
            }
        } catch (Exception e) {
            LOGGER.error("Error loading audio: {}", soundPath, e);
            return;
        }

        if (rawAudio == null || channels == 0 || sampleRate == 0) {
            LOGGER.error("Invalid audio data for: {}", soundPath);
            return;
        }

        int format = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

        // Create OpenAL buffer and source
        this.buffer = alGenBuffers();
        alBufferData(this.buffer, format, rawAudio, sampleRate);

        this.source = alGenSources();
        alSourcei(this.source, AL_BUFFER, this.buffer);
        alSourcef(this.source, AL_PITCH, this.pitch);
        alSourcei(this.source, AL_LOOPING, this.loop ? AL_TRUE : AL_FALSE);

        // Free raw audio data (now copied into OpenAL)
        MemoryUtil.memFree(rawAudio);

        this.valid = true;
    }

    public void play() {
        if (!valid) return;
        alSourcePlay(source);
    }

    public void stop() {
        if (!valid) return;
        alSourceStop(source);
    }

    public void pause() {
        if (!valid) return;
        alSourcePause(source);
    }

    public void setVolume(float vol) {
        if (!valid) return;
        this.currentVolume = Math.max(0f, Math.min(1f, vol));
        alSourcef(source, AL_GAIN, this.currentVolume);
    }

    public void setPosition(Vec3 pos) {
        if (!valid) return;
        alSource3f(source, AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
    }

    /**
     * 设置衰减模式。
     *
     * @param mode "none"=无衰减, "linear"=线性衰减, "inverse"=逆距离衰减
     */
    public void setAttenuation(String mode) {
        if (!valid) return;
        float factor;
        switch (mode) {
            case "none" -> factor = 0f;
            case "inverse" -> factor = 2f;
            default -> factor = 1f; // linear
        }
        alSourcef(source, AL_ROLLOFF_FACTOR, factor);
    }

    /**
     * 每帧更新 — 用于淡入/淡出过渡。
     * 当前基础实现：无渐变直接生效。渐变由 AudioTrackPlayer 管理。
     */
    public void update() {
        // Reserved for future frame-based fade updates.
        // Currently AudioTrackPlayer drives fades via setVolume().
    }

    public boolean isPlaying() {
        if (!valid) return false;
        return alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING;
    }

    public void cleanup() {
        if (source != 0) {
            alSourceStop(source);
            alDeleteSources(source);
            source = 0;
        }
        if (buffer != 0) {
            alDeleteBuffers(buffer);
            buffer = 0;
        }
        valid = false;
    }

    public float getDuration() {
        return duration;
    }

    public float getCurrentTime() {
        if (!valid) return 0f;
        return alGetSourcef(source, AL_SEC_OFFSET);
    }

    public boolean isValid() {
        return valid;
    }

    // ── Helpers ──

    private static byte[] readAllBytes(InputStream is) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int pos = 0;
        int read;
        while ((read = is.read(buf, pos, buf.length - pos)) != -1) {
            pos += read;
            if (pos == buf.length) {
                byte[] bigger = new byte[buf.length * 2];
                System.arraycopy(buf, 0, bigger, 0, buf.length);
                buf = bigger;
            }
        }
        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }
}
