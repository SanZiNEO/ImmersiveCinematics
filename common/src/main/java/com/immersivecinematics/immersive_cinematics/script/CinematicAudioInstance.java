package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.media.audio.ExternalPcmDecode;
import com.immersivecinematics.immersive_cinematics.media.video.Ffmpeg;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * AUDIO 轨道实例：继承 {@link AbstractTickableSoundInstance}，由原版 SoundEngine 统一管理空间 / 衰减 / 分类音量 / 暂停。
 * 自定义流通过 {@link CustomStreamProvider} + {@code SoundEngineMixin} 提供给原版，不依赖 Forge 补丁。
 *
 * 解码按扩展名分流：{@code source=minecraft} 仅 OGG；文件源 {@code .ogg/.wav} 同步内存解码（构造即就绪）；
 * 白名单其余格式（见 {@link ExternalPcmDecode#isSupportedExtension(String)}）走 ffmpeg 整段异步解码：
 * 构造立即返回、{@link #isUsable()} 在结果就绪前为 {@code false}，期间 {@link #play()} / seek 只记待办，
 * 就绪后由渲染线程下一帧的 {@link #ensurePlaying(double, float)} 起播。暂停 / seek / 循环语义不变。
 */
public class CinematicAudioInstance extends AbstractTickableSoundInstance implements CustomStreamProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    private static final long FAIL_LOG_INTERVAL_MS = 60_000L;

    private final boolean loop;
    private final float audioPitch;
    private final String id;
    /** 实例存活（构造成功且未 cleanup）；异步解码回调据此丢弃已失效的结果。 */
    private volatile boolean valid = false;
    /** PCM 已就绪（同步解码在构造内完成，外部解码在回调里置位）。 */
    private volatile boolean ready = false;
    /** 解码失败（异步路径；失败后不再重试，由调用方 cleanup + 跳过该 clip）。 */
    private volatile boolean failed = false;
    private boolean playing = false;
    /** 未就绪期间收到的 seek 目标（秒），就绪时应用到 {@link #seekOffset}。 */
    private volatile double pendingSeek = 0.0;
    /** 未就绪期间收到过 play()（就绪后仍需渲染线程再次 play/ensurePlaying 才真正起播）。 */
    private volatile boolean pendingPlay = false;
    private volatile float duration = 0f;
    /** 编辑器 seek 目标偏移（秒），play() 时把 PCM 游标移到此处 */
    private volatile double seekOffset = 0.0;

    private volatile ByteBuffer pcmData;
    private volatile int channels;
    private volatile int sampleRate;

    /**
     * 解码音频并建立实例（解码失败抛 {@link RuntimeException}，由调用方记为失败 clip）。
     *
     * @param fileName   文件名（{@code sourceType=minecraft} 时为资源 id，否则为资源目录下的相对路径）
     * @param sourceType {@code "minecraft"} = 从资源包读取，其余 = 本地文件
     * @param loop       是否循环
     * @param pitch      音调倍率（1 = 原值）
     * @param category   声音分类（分类音量 / 静音随原版走）
     */
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

        // 先置 valid：异步解码可能在本构造返回前完成，回调据此判断实例仍有效
        this.valid = true;

        DecodeResult result = decode(fileName, sourceType);
        if (result != null) {
            applyDecoded(result.rawAudio, result.channels, result.sampleRate, result.duration);
            LOGGER.debug("audio decode: file={} bytes={} ch={} sr={} dur={}",
                    fileName, pcmData.remaining(), channels, sampleRate, duration);
        } else {
            LOGGER.debug("audio decode pending (external ffmpeg): file={}", fileName);
        }
    }

    /** 安装解码结果：字段写完后最后置 {@link #ready}（volatile 写负责发布）。 */
    private void applyDecoded(ByteBuffer data, int ch, int sr, float dur) {
        this.pcmData = data;
        this.channels = ch;
        this.sampleRate = sr;
        this.duration = dur;
        this.seekOffset = Math.max(0.0, Math.min(pendingSeek, dur));
        this.ready = true;
        if (pendingPlay) {
            // 起播由渲染线程的下一帧 ensurePlaying 完成，这里只留痕
            LOGGER.debug("external audio ready after pending play: ch={} sr={} dur={}", ch, sr, dur);
        }
    }

    // ===== 原版 SoundEngine 集成 =====

    @Override
    public boolean canPlaySound() {
        // 外部解码未就绪前不能交给 SoundEngine（没有 PCM 可读）
        return valid && ready;
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

    // ===== 对外 API =====

    /**
     * 开始播放（幂等）：从 {@link #seekOffset} 处起播，之后交由原版 SoundEngine 驱动。
     * 外部解码未就绪时只记 {@link #pendingPlay}，PCM 就绪后由渲染线程再次调用本方法（或
     * {@link #ensurePlaying(double, float)}）才真正起播——不在解码线程里碰 SoundEngine。
     */
    public void play() {
        if (!valid || failed || playing) return;
        if (!ready) {
            pendingPlay = true;
            return;
        }
        pendingPlay = false;
        if (pcmData != null) {
            pcmData.position(byteOffset(seekOffset));
        }
        playing = true;
        Minecraft.getInstance().getSoundManager().play(this);
    }

    /** 停止实例（幂等）：仅置位并交还 SoundEngine，PCM 数据保留以便再次 play；同时取消未就绪期间的待播请求。 */
    public void stopInstance() {
        pendingPlay = false;
        if (!valid || !playing) return;
        playing = false;
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    /** 空实现：暂停语义由 {@code AudioTrackPlayer} 走 SoundEngine 全局 pause 处理。 */
    public void pause() {
    }

    /** 空实现：恢复语义由 {@code AudioTrackPlayer} 走 SoundEngine 全局 resume 处理。 */
    public void resume() {
    }

    /** 设置音量（0~1，原版据此乘分类音量 / 衰减）。 */
    public void setVolume(float vol) {
        this.volume = vol;
    }

    /** 设置世界坐标位置（空间音源由原版按听者距离衰减）。 */
    public void setPosition(Vec3 pos) {
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
    }

    /**
     * 设置衰减模式。
     *
     * @param mode     {@code "none"} = 无衰减，其它值 = 线性衰减
     * @param distance 距离参数（当前实现不使用，由原版按音量曲线计算）
     */
    public void setAttenuation(String mode, float distance) {
        this.attenuation = "none".equals(mode) ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
    }

    /** 设置衰减模式（{@code "none"} = 无衰减，其它值 = 线性衰减）。 */
    public void setAttenuation(String mode) {
        setAttenuation(mode, 16f);
    }

    /** 背景音（music）强制相对听者、无空间性 */
    public void setRelative(boolean rel) {
        this.relative = rel;
    }

    /**
     * 编辑器 seek：把 PCM 游标移到目标秒；若正在播放则先停止（由 syncToTime 负责按需重启）。
     * 外部解码未就绪时只记 {@link #pendingSeek}，就绪时按该目标定位。
     *
     * @param seconds 目标片段内时间（秒，钳制到 [0, duration]）
     */
    public void seekTo(double seconds) {
        if (!valid || failed) return;
        if (!ready) {
            pendingSeek = Math.max(0.0, seconds);
            return;
        }
        seekOffset = Math.max(0.0, Math.min(seconds, duration));
        pendingSeek = seekOffset;
        if (playing) {
            stopInstance();
        }
    }

    /**
     * 编辑器定位：seek + 音量，并在非暂停场景下立即以新位置重启播放。
     *
     * @param targetLocalTime 目标片段内时间（秒）
     * @param volume          目标音量（0~1）
     * @param fadeIn          淡入时长（秒；当前实现不重放淡入，保留给调用方折算音量）
     */
    public void syncToTime(double targetLocalTime, float volume, float fadeIn) {
        setVolume(volume);
        seekTo(targetLocalTime);
        if (!playing) {
            play();
        }
    }

    /**
     * 每帧驱动（渲染线程）：写音量，必要时按当前执行时间起播。
     * 外部解码未就绪时只记 {@link #pendingSeek} / {@link #pendingPlay} 并返回（静音等待，不阻塞）；
     * 就绪且未在播时按 {@code localTime} 定位并起播；已在播则只更新音量，不打断播放。
     *
     * @param localTime 当前片段内时间（秒）
     * @param volume    当前音量（0~1，已含淡入淡出折算）
     */
    public void ensurePlaying(double localTime, float volume) {
        setVolume(volume);
        if (!valid || failed) return;
        if (!ready) {
            pendingSeek = Math.max(0.0, localTime);
            pendingPlay = true;
            return;
        }
        if (playing) return;
        seekOffset = Math.max(0.0, Math.min(localTime, duration));
        pendingSeek = seekOffset;
        play();
    }

    private int byteOffset(double seconds) {
        if (pcmData == null || sampleRate <= 0 || channels <= 0) return 0;
        long bytes = (long) (seconds * sampleRate * channels * 2);
        int limit = pcmData.limit();
        return (int) Math.max(0, Math.min(bytes, limit));
    }

    /** 空实现：位置 / 音量由 {@code AudioTrackPlayer} 每帧写入字段，原版 SoundEngine 自行读取。 */
    public void update() {
    }

    /** 是否正在发声（已 play 且未被 SoundEngine 停止）。 */
    public boolean isPlaying() {
        return valid && playing && !isStopped();
    }

    /** 释放实例：停播并置为无效（再次 play / seek 均不再生效；异步解码结果此后丢弃）。 */
    public void cleanup() {
        // 先置无效：解码线程的完成回调据此丢弃结果，不再安装 PCM
        valid = false;
        ready = false;
        pendingPlay = false;
        if (playing) {
            Minecraft.getInstance().getSoundManager().stop(this);
        }
        playing = false;
        stop();
    }

    /** 解码得到的时长（秒）；外部解码未就绪时为 0。 */
    public float getDuration() { return duration; }

    /** 实例是否可用（构造成功且未 cleanup；外部解码未就绪时仍为 true）。 */
    public boolean isValid() { return valid; }

    /** 可以真正起播：PCM 已就绪、未失败、未 cleanup。 */
    public boolean isUsable() { return valid && ready && !failed; }

    /** 解码失败（外部解码路径）；失败后调用方应 cleanup 并跳过该 clip。 */
    public boolean isFailed() { return failed; }

    // ===== 解码（失败即抛 RuntimeException，可见） =====

    /**
     * 按扩展名分流解码。
     *
     * @return 同步解码结果；外部异步解码返回 {@code null}（结果稍后由回调安装）
     */
    private DecodeResult decode(String fileName, String sourceType) {
        if ("minecraft".equals(sourceType)) {
            return decodeOggFromMinecraft(fileName);
        }
        Path filePath = ResourcePath.resolve(fileName);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("音频文件不存在: " + filePath);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ogg")) {
            return decodeOggFromFile(filePath);
        }
        if (lower.endsWith(".wav")) {
            return decodeWav(filePath);
        }
        if (ExternalPcmDecode.isSupportedExtension(lower)) {
            startExternalDecode(filePath);
            return null;
        }
        throw new RuntimeException("不支持的音频格式: " + fileName
                + "（支持 .ogg/.wav/.mp3/.aac/.flac/.m4a/.opus/.wma/.oga/.ogx）");
    }

    /**
     * 白名单格式：ffmpeg 整段异步解码，静音等待、无时长上限。
     * ffmpeg 缺失属于配置错误，构造期直接抛出（调用方记为失败 clip，只报一次）。
     */
    private void startExternalDecode(Path filePath) {
        Path ffmpeg = Ffmpeg.encoderAvailable() ? Ffmpeg.ffmpeg() : null;
        if (ffmpeg == null) {
            throw new RuntimeException("缺少 ffmpeg，无法解码 " + filePath.getFileName()
                    + "（请把 ffmpeg 放到 <游戏目录>/ffmpeg/）");
        }
        ExternalPcmDecode.decodeAsync(ffmpeg, filePath)
                .whenComplete((pcm, error) -> onExternalDecoded(filePath, pcm, error));
    }

    /** 解码线程回调：实例已失效则丢弃；失败则置 failed 并留痕，成功则安装 PCM 并置 ready。 */
    private void onExternalDecoded(Path filePath, ExternalPcmDecode.Pcm pcm, Throwable error) {
        if (!valid) {
            return;
        }
        if (error != null || pcm == null) {
            this.failed = true;
            this.ready = false;
            String detail;
            if (error == null) {
                detail = "未知错误";
            } else {
                detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
            String message = "外部音频解码失败：" + filePath + " — " + detail;
            LOGGER.error(message, error);
            ErrorLog.logRateLimited("Audio", "external-decode:" + filePath, FAIL_LOG_INTERVAL_MS, message, error);
            return;
        }
        applyDecoded(pcm.data(), pcm.channels(), pcm.sampleRate(), pcm.durationSeconds());
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
