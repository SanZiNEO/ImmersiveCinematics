package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.ResourcePath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * AUDIO 轨道实例 — 回归原版 SoundEngine（0.3.5 第4轮，跨平台 common Mixin 方案）。
 * <p>
 * 继承 {@link AbstractTickableSoundInstance}，由原版 SoundEngine 统一管理空间/衰减/分类音量/暂停。
 * 自定义流通过 {@link CustomStreamProvider} + {@code SoundEngineMixin} 提供给原版，不依赖 Forge 补丁。
 */
public class CinematicAudioInstance extends AbstractTickableSoundInstance implements CustomStreamProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    private final boolean loop;
    private final float audioPitch;
    private final String id;
    private boolean valid = false;
    private boolean playing = false;
    private float duration = 0f;
    private float currentVolume = 1.0f;
    /** 编辑器 seek 目标偏移（秒），play() 时把 PCM 游标移到此处 */
    private float seekOffset = 0f;

    private ByteBuffer pcmData;
    private int channels;
    private int sampleRate;

    public CinematicAudioInstance(String fileName, String sourceType, boolean loop, float pitch, SoundSource category) {
        super(SoundEvent.createVariableRangeEvent(new ResourceLocation(
                        "immersive_cinematics", "audio_" + Integer.toHexString(Objects.hash(fileName, sourceType)))),
                category, SoundInstance.createUnseededRandom());
        this.loop = loop;
        this.audioPitch = pitch;
        this.pitch = pitch;
        this.id = "audio_" + Integer.toHexString(Objects.hash(fileName, sourceType));

        if (!fileName.chars().allMatch(c -> c < 128)) {
            LOGGER.warn("音频文件名包含非 ASCII 字符: {} — Windows 下可能无法解码，建议使用英文命名", fileName);
        }

        DecodeResult result = decode(fileName, sourceType);
        this.pcmData = result.rawAudio;
        this.channels = result.channels;
        this.sampleRate = result.sampleRate;
        this.duration = result.duration;
        this.valid = true;

        LOGGER.debug("audio decode: file={} bytes={} ch={} sr={} dur={}",
                fileName, pcmData.remaining(), channels, sampleRate, duration);
    }

    // ===== 原版 SoundEngine 集成 =====

    @Override
    public boolean canPlaySound() {
        return valid;
    }

    @Override
    public boolean canStartSilent() {
        // 淡入从 0 音量开始：原版 SoundEngine 遇 0 音量会直接跳过播放，必须允许静音启动
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

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new CinematicAudioStream(pcmData, channels, sampleRate, loop));
    }

    @Override
    public void tick() {
        // 位置/音量由 AudioTrackPlayer 每帧通过 setVolume/setPosition 写入字段，原版自动读取
    }

    // ===== 对外兼容 API =====

    public void play() {
        if (!valid || playing) return;
        if (pcmData != null) {
            pcmData.position(byteOffset(seekOffset));
        }
        playing = true;
        Minecraft.getInstance().getSoundManager().play(this);
    }

    public void stopInstance() {
        if (!valid || !playing) return;
        playing = false;
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    public void pause() {
        // 暂停语义由 AudioTrackPlayer 走 SoundEngine.pause() 全局处理
    }

    public void resume() {
        // 同上
    }

    public void setVolume(float vol) {
        this.currentVolume = vol;
        this.volume = vol;
    }

    public void setPosition(Vec3 pos) {
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
    }

    public void setAttenuation(String mode, float distance) {
        this.attenuation = "none".equals(mode) ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
    }

    public void setAttenuation(String mode) {
        setAttenuation(mode, 16f);
    }

    /** 背景音（music）强制相对听者、无空间性 */
    public void setRelative(boolean rel) {
        this.relative = rel;
    }

    /** 编辑器 seek：把 PCM 游标移到目标秒；若正在播放则先停止（由 syncToTime 负责按需重启） */
    public void seekTo(float seconds) {
        if (!valid) return;
        seekOffset = Math.max(0f, Math.min(seconds, duration));
        if (playing) {
            stopInstance();
        }
    }

    /** 编辑器定位：seek + 音量，并在非暂停场景下立即以新位置重启播放 */
    public void syncToTime(float targetLocalTime, float volume, float fadeIn) {
        setVolume(volume);
        seekTo(targetLocalTime);
        if (!playing) {
            play();
        }
    }

    private int byteOffset(float seconds) {
        if (pcmData == null || sampleRate <= 0 || channels <= 0) return 0;
        long bytes = (long) (seconds * sampleRate * channels * 2);
        int limit = pcmData.limit();
        return (int) Math.max(0, Math.min(bytes, limit));
    }

    public void update() {
        // 原版 tick 已自动处理
    }

    public boolean isPlaying() {
        return valid && playing && !isStopped();
    }

    public void cleanup() {
        if (valid && playing) {
            Minecraft.getInstance().getSoundManager().stop(this);
        }
        playing = false;
        valid = false;
        stop();
    }

    public float getDuration() { return duration; }

    public int getSourceState() {
        return playing ? 1 : 0;
    }

    public float getGain() {
        return currentVolume;
    }

    public int getOpenAlError() {
        return 0;
    }

    public float getCurrentTime() {
        return 0f;
    }

    public boolean isValid() { return valid; }

    // ===== 解码（失败即抛 RuntimeException，可见） =====

    private static DecodeResult decode(String fileName, String sourceType) {
        if ("minecraft".equals(sourceType)) {
            return decodeOggFromMinecraft(fileName);
        }
        Path filePath = ResourcePath.resolve(fileName);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("音频文件不存在: " + filePath);
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".ogg")) {
            return decodeOggFromFile(filePath);
        }
        if (lower.endsWith(".wav")) {
            return decodeWav(filePath);
        }
        throw new RuntimeException("不支持的音频格式: " + fileName + "（支持 .ogg/.wav）");
    }

    private static DecodeResult decodeOggFromFile(Path oggPath) {
        IntBuffer chBuf = BufferUtils.createIntBuffer(1);
        IntBuffer srBuf = BufferUtils.createIntBuffer(1);
        ShortBuffer decoded = STBVorbis.stb_vorbis_decode_filename(oggPath.toString(), chBuf, srBuf);
        if (decoded == null) {
            throw new RuntimeException("OGG 解码失败: " + oggPath);
        }
        int ch = chBuf.get(0);
        int sr = srBuf.get(0);
        int totalBytes = decoded.remaining() * 2;
        float dur = (float) decoded.remaining() / (float) sr / (float) ch;
        ByteBuffer raw = BufferUtils.createByteBuffer(totalBytes);
        raw.asShortBuffer().put(decoded);
        return new DecodeResult(raw, ch, sr, dur);
    }

    private static DecodeResult decodeOggFromMinecraft(String fileName) {
        ResourceLocation loc = new ResourceLocation(fileName);
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(loc).orElse(null);
        if (resource == null) {
            throw new RuntimeException("Minecraft 音频资源不存在: " + fileName);
        }
        try (InputStream is = resource.open()) {
            byte[] bytes = readAllBytes(is);
            ByteBuffer buf = BufferUtils.createByteBuffer(bytes.length);
            buf.put(bytes).flip();

            IntBuffer error = BufferUtils.createIntBuffer(1);
            long handle = STBVorbis.stb_vorbis_open_memory(buf, error, null);
            if (handle == 0) {
                throw new RuntimeException("内存 OGG 解码失败: " + fileName);
            }
            STBVorbisInfo info = STBVorbisInfo.malloc();
            STBVorbis.stb_vorbis_get_info(handle, info);
            int ch = info.channels();
            int sr = info.sample_rate();
            int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
            float dur = (float) totalSamples / (float) sr / (float) ch;

            ByteBuffer raw = BufferUtils.createByteBuffer(totalSamples * ch * 2);
            ShortBuffer rawShort = raw.asShortBuffer();
            STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, ch, rawShort);

            info.free();
            STBVorbis.stb_vorbis_close(handle);
            return new DecodeResult(raw, ch, sr, dur);
        } catch (IOException e) {
            throw new RuntimeException("读取 Minecraft 音频资源失败: " + fileName, e);
        }
    }

    private static DecodeResult decodeWav(Path wavPath) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile());
            AudioFormat fmt = ais.getFormat();
            int ch = fmt.getChannels();
            int sr = (int) fmt.getSampleRate();
            int bits = fmt.getSampleSizeInBits();

            byte[] allBytes = readAllBytes(ais);
            ais.close();

            ByteBuffer buffer;
            if (bits == 16) {
                buffer = BufferUtils.createByteBuffer(allBytes.length);
                buffer.put(allBytes);
                buffer.flip();
            } else if (bits == 8) {
                buffer = BufferUtils.createByteBuffer(allBytes.length * 2);
                ShortBuffer sb = buffer.asShortBuffer();
                for (byte b : allBytes) {
                    sb.put((short) ((b & 0xFF) - 128 << 8));
                }
            } else {
                throw new RuntimeException("不支持的 WAV 位深: " + bits);
            }
            float dur = (float) buffer.limit() / 2f / (float) sr / (float) ch;
            return new DecodeResult(buffer, ch, sr, dur);
        } catch (Exception e) {
            throw new RuntimeException("WAV 解码失败: " + wavPath, e);
        }
    }

    /** 编辑器波形峰值解码（失败即抛） */
    public static float[] decodePeaks(String fileName, String sourceType, int buckets) {
        DecodeResult r = decode(fileName, sourceType);
        r.rawAudio.rewind();
        int n = r.rawAudio.remaining() / 2;
        short[] samples = new short[n];
        for (int i = 0; i < n; i++) samples[i] = r.rawAudio.getShort();
        float[] peaks = new float[Math.max(1, buckets)];
        int per = Math.max(1, n / peaks.length);
        for (int b = 0; b < peaks.length; b++) {
            float max = 0;
            int from = b * per, to = Math.min(n, from + per);
            for (int i = from; i < to; i++) max = Math.max(max, Math.abs(samples[i]) / 32768f);
            peaks[b] = max;
        }
        return peaks;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
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

    // ===== PCM 流（喂给原版 SoundEngine） =====

    private static final class CinematicAudioStream implements AudioStream {
        private final AudioFormat format;
        private final ByteBuffer data;
        private final boolean loop;

        CinematicAudioStream(ByteBuffer data, int channels, int sampleRate, boolean loop) {
            this.data = data;
            this.loop = loop;
            this.format = new AudioFormat(sampleRate, 16, channels, true, false);
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int size) {
            ByteBuffer out = BufferUtils.createByteBuffer(size);
            while (out.hasRemaining()) {
                if (!data.hasRemaining()) {
                    if (!loop) break;
                    data.rewind();
                }
                int n = Math.min(out.remaining(), data.remaining());
                byte[] arr = new byte[n];
                data.get(arr);
                out.put(arr);
            }
            out.flip();
            return out;
        }

        @Override
        public void close() {
            // PCM 数据由实例持有
        }
    }

    private static final class DecodeResult {
        final ByteBuffer rawAudio;
        final int channels;
        final int sampleRate;
        final float duration;

        DecodeResult(ByteBuffer rawAudio, int channels, int sampleRate, float duration) {
            this.rawAudio = rawAudio;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.duration = duration;
        }
    }
}
