package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.ResourcePath;
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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET;

/**
 * 单声源 OpenAL 音频实例 — 管理 OGG/WAV 音频的加载、播放、空间定位。
 * <p>
 * 文件位于 {@code resource/} 目录下，通过文件扩展名自动选择解码方式：
 * <ul>
 *   <li>{@code .ogg} → STBVorbis 解码</li>
 *   <li>{@code .wav} → {@link javax.sound.sampled.AudioSystem} 解码</li>
 * </ul>
 */
public class CinematicAudioInstance {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    private int source = 0;
    private int buffer = 0;
    private boolean valid = false;
    private float duration = 0f;

    private final boolean loop;
    private final float pitch;
    private float currentVolume = 1.0f;

    public CinematicAudioInstance(String fileName, String sourceType, boolean loop, float pitch) {
        this.loop = loop;
        this.pitch = pitch;

        ShortBuffer rawAudio = null;
        int channels = 0;
        int sampleRate = 0;

        try {
            if ("minecraft".equals(sourceType)) {
                // 走 Minecraft SoundManager 资源
                OggDecodeResult result = decodeOggFromMinecraft(fileName);
                if (result == null) return;
                rawAudio = result.rawAudio;
                channels = result.channels;
                sampleRate = result.sampleRate;
                this.duration = result.duration;
            } else {
                // 从 resource/ 目录加载
                Path filePath = ResourcePath.resolve(fileName);
                if (!Files.exists(filePath)) {
                    LOGGER.error("Audio file not found: {}", filePath);
                    return;
                }

                String lower = fileName.toLowerCase();
                if (lower.endsWith(".ogg")) {
                    OggDecodeResult result = decodeOggFromFile(filePath);
                    if (result == null) return;
                    rawAudio = result.rawAudio;
                    channels = result.channels;
                    sampleRate = result.sampleRate;
                    this.duration = result.duration;
                } else if (lower.endsWith(".wav")) {
                    WavDecodeResult result = decodeWav(filePath);
                    if (result == null) return;
                    rawAudio = result.rawAudio;
                    channels = result.channels;
                    sampleRate = result.sampleRate;
                    this.duration = result.duration;
                } else {
                    LOGGER.error("Unsupported audio format: {} (supported: .ogg, .wav)", fileName);
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error loading audio: {}", fileName, e);
            return;
        }

        if (rawAudio == null || channels == 0 || sampleRate == 0) {
            LOGGER.error("Invalid audio data for: {}", fileName);
            return;
        }

        int format = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

        this.buffer = alGenBuffers();
        alBufferData(this.buffer, format, rawAudio, sampleRate);

        this.source = alGenSources();
        alSourcei(this.source, AL_BUFFER, this.buffer);
        alSourcef(this.source, AL_PITCH, this.pitch);
        alSourcei(this.source, AL_LOOPING, this.loop ? AL_TRUE : AL_FALSE);

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
        alSourceRewind(source);
    }

    public void pause() {
        if (!valid) return;
        alSourcePause(source);
    }

    public void setVolume(float vol) {
        this.currentVolume = vol;
        if (valid) alSourcef(source, AL_GAIN, vol);
    }

    public void setPosition(Vec3 pos) {
        if (valid) alSource3f(source, AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
    }

    public void setAttenuation(String mode) {
        if (!valid) return;
        switch (mode) {
            case "none" -> alSourcef(source, AL_ROLLOFF_FACTOR, 0f);
            case "linear" -> alSourcef(source, AL_ROLLOFF_FACTOR, 1f);
            case "inverse" -> alSourcef(source, AL_ROLLOFF_FACTOR, 2f);
            default -> alSourcef(source, AL_ROLLOFF_FACTOR, 1f);
        }
    }

    public void update() {}

    public boolean isPlaying() {
        if (!valid) return false;
        return alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING;
    }

    public void cleanup() {
        if (source != 0) {
            alDeleteSources(source);
            source = 0;
        }
        if (buffer != 0) {
            alDeleteBuffers(buffer);
            buffer = 0;
        }
        valid = false;
    }

    public float getDuration() { return duration; }

    public float getCurrentTime() {
        if (!valid) return 0f;
        return alGetSourcef(source, AL_SEC_OFFSET);
    }

    public boolean isValid() { return valid; }

    // ========== OGG decoding (STBVorbis) ==========

    private static class OggDecodeResult {
        final ShortBuffer rawAudio;
        final int channels;
        final int sampleRate;
        final float duration;

        OggDecodeResult(ShortBuffer rawAudio, int channels, int sampleRate, float duration) {
            this.rawAudio = rawAudio;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.duration = duration;
        }
    }

    private static OggDecodeResult decodeOggFromFile(Path oggPath) {
        IntBuffer chBuf = BufferUtils.createIntBuffer(1);
        IntBuffer srBuf = BufferUtils.createIntBuffer(1);
        ShortBuffer raw = STBVorbis.stb_vorbis_decode_filename(oggPath.toString(), chBuf, srBuf);
        if (raw == null) {
            LOGGER.error("Failed to decode OGG file: {}", oggPath);
            return null;
        }
        int channels = chBuf.get(0);
        int sampleRate = srBuf.get(0);
        int totalSamples = raw.remaining();
        float duration = (float) totalSamples / (float) sampleRate / (float) channels;
        return new OggDecodeResult(raw, channels, sampleRate, duration);
    }

    private static OggDecodeResult decodeOggFromMinecraft(String fileName) {
        try {
            ResourceLocation loc = new ResourceLocation(fileName);
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(loc).orElse(null);
            if (resource == null) {
                LOGGER.error("Minecraft sound resource not found: {}", fileName);
                return null;
            }
            try (InputStream is = resource.open()) {
                byte[] bytes = readAllBytes(is);
                ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
                buf.put(bytes).flip();

                IntBuffer error = BufferUtils.createIntBuffer(1);
                long handle = STBVorbis.stb_vorbis_open_memory(buf, error, null);
                if (handle == 0) {
                    MemoryUtil.memFree(buf);
                    LOGGER.error("Failed to decode OGG from memory for: {}", fileName);
                    return null;
                }

                STBVorbisInfo info = STBVorbisInfo.malloc();
                STBVorbis.stb_vorbis_get_info(handle, info);
                int ch = info.channels();
                int sr = info.sample_rate();
                int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                float dur = (float) totalSamples / (float) sr / (float) ch;

                ShortBuffer raw = MemoryUtil.memAllocShort(totalSamples * ch);
                STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, ch, raw);
                raw.flip();

                info.free();
                STBVorbis.stb_vorbis_close(handle);
                MemoryUtil.memFree(buf);

                return new OggDecodeResult(raw, ch, sr, dur);
            }
        } catch (Exception e) {
            LOGGER.error("Error loading Minecraft sound: {}", fileName, e);
            return null;
        }
    }

    // ========== WAV decoding (javax.sound.sampled) ==========

    private static class WavDecodeResult {
        final ShortBuffer rawAudio;
        final int channels;
        final int sampleRate;
        final float duration;

        WavDecodeResult(ShortBuffer rawAudio, int channels, int sampleRate, float duration) {
            this.rawAudio = rawAudio;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.duration = duration;
        }
    }

    private static WavDecodeResult decodeWav(Path wavPath) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile());
            AudioFormat fmt = ais.getFormat();

            int ch = fmt.getChannels();
            int sr = (int) fmt.getSampleRate();
            int bits = fmt.getSampleSizeInBits();

            byte[] allBytes = readAllBytes(ais);
            ais.close();

            ShortBuffer buffer;
            if (bits == 16) {
                // PCM 16-bit signed little-endian → 转 ShortBuffer
                ShortBuffer src = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                ShortBuffer tmp = MemoryUtil.memAllocShort(src.remaining());
                tmp.put(src);
                tmp.flip();
                buffer = tmp;
            } else if (bits == 8) {
                // PCM 8-bit unsigned → 转 16-bit signed
                buffer = MemoryUtil.memAllocShort(allBytes.length);
                for (byte b : allBytes) {
                    buffer.put((short) ((b & 0xFF) - 128 << 8));
                }
                buffer.flip();
            } else {
                LOGGER.error("Unsupported WAV bit depth: {}", bits);
                return null;
            }

            float dur = (float) buffer.remaining() / (float) sr / (float) ch;
            return new WavDecodeResult(buffer, ch, sr, dur);
        } catch (Exception e) {
            LOGGER.error("Failed to decode WAV file: {}", wavPath, e);
            return null;
        }
    }

    // ========== Helpers ==========

    private static byte[] readAllBytes(InputStream is) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = is.read(buf, total, buf.length - total)) > 0) {
            total += n;
            if (total == buf.length) {
                byte[] bigger = new byte[buf.length * 2];
                System.arraycopy(buf, 0, bigger, 0, buf.length);
                buf = bigger;
            }
        }
        byte[] result = new byte[total];
        System.arraycopy(buf, 0, result, 0, total);
        return result;
    }
}
