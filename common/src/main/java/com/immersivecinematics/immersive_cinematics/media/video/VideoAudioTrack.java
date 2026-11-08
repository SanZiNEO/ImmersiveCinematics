package com.immersivecinematics.immersive_cinematics.media.video;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * 视频音轨跟随播放时钟：每帧由渲染线程调用 {@link #syncToTime(double)}，负责起播、暂停、seek / 循环重建。
 *
 * 判定规则：
 * <ul>
 *   <li>暂停：{@code |localTime - lastLocalTime| < 1e-6} 持续超过 100ms → 停掉实例；时间恢复推进后按当前时间重建。</li>
 *   <li>漂移：已播实例按「起播时刻 + 墙上时钟」推算期望位置，与画面时间偏差超过 0.25s（或画面时间倒退超过 0.05s）
 *       → 停旧实例并按画面时间重建。外部 ffmpeg 的 {@code -ss} 是唯一 seek 手段，不做进程内跳读。</li>
 * </ul>
 *
 * 线程边界：全部状态只在渲染线程读写，本类不自建线程；实际 PCM 进程由 {@link VideoAudioInstance} /
 * {@link ProcessPcmStream} 在 SoundEngine 侧管理。{@link #close()} 幂等。
 */
public final class VideoAudioTrack implements AutoCloseable {

    /** 时间推进判定阈值（秒）：小于该值视为「同一帧时间」。 */
    private static final double TIME_EPSILON = 1e-6;
    /** 时间停滞多久视为暂停。 */
    private static final long PAUSE_THRESHOLD_NANOS = 100_000_000L;
    /** 画面时间倒退容差（秒）。 */
    private static final double SEEK_BACK_TOLERANCE = 0.05;
    /** 音画漂移容差（秒）。 */
    private static final double DRIFT_TOLERANCE = 0.25;

    private final Path path;
    private final boolean hasAudio;

    private VideoAudioInstance instance;
    private double startedAt;
    private long startedWallNanos;
    private double lastLocalTime = Double.NaN;
    private long lastChangeNanos;
    private boolean closed;

    /**
     * @param path     视频文件路径（原样传给 ffmpeg）
     * @param hasAudio 视频是否含音轨；{@code false} 时所有方法均为 no-op
     */
    public VideoAudioTrack(Path path, boolean hasAudio) {
        this.path = path;
        this.hasAudio = hasAudio && path != null;
    }

    /**
     * 对齐到画面时间（渲染线程每帧调用）。
     *
     * @param localTime 视频片段内时间（秒）
     */
    public void syncToTime(double localTime) {
        if (!hasAudio || closed || !Double.isFinite(localTime)) {
            return;
        }
        long now = System.nanoTime();
        if (Double.isNaN(lastLocalTime) || Math.abs(localTime - lastLocalTime) >= TIME_EPSILON) {
            lastLocalTime = localTime;
            lastChangeNanos = now;
        }

        // 时间停滞超过 100ms：暂停（停实例，保留 null，恢复推进后重建）
        if (now - lastChangeNanos > PAUSE_THRESHOLD_NANOS) {
            stopInstance();
            return;
        }

        if (instance == null) {
            start(localTime, now);
            return;
        }

        double expected = startedAt + (now - startedWallNanos) / 1_000_000_000.0;
        if (localTime < startedAt - SEEK_BACK_TOLERANCE
                || Math.abs(localTime - expected) > DRIFT_TOLERANCE) {
            stopInstance();
            start(localTime, now);
        }
    }

    /** 幂等关闭：停掉当前实例，之后所有 sync 调用均无效。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopInstance();
    }

    /** 按画面时间新建实例并交给原版 SoundEngine。 */
    private void start(double localTime, long now) {
        VideoAudioInstance created = new VideoAudioInstance(path, Math.max(0.0, localTime));
        Minecraft.getInstance().getSoundManager().play(created);
        instance = created;
        startedAt = localTime;
        startedWallNanos = now;
    }

    /** 停掉当前实例（幂等）；引用置空以便下次重建。 */
    private void stopInstance() {
        VideoAudioInstance current = instance;
        instance = null;
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
        }
    }
}
