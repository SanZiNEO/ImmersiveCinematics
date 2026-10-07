package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 时间轴 — 脚本的核心数据容器
 * <p>
 * 包含总时长和并行轨道数组。
 * 时间层级：总时间轴 → 轨道 → 片段 → 关键帧
 */
public class Timeline {

    /** 脚本总时长（秒），负数 = 含无限时长片段（无法预计算） */
    private final float totalDuration;

    /** 并行轨道数组 */
    private final List<TimelineTrack> tracks;

    /** 宏观循环区间起点 a（秒）：{@code loop_start}，缺省 0（负值按 0 处理） */
    private final float loopStart;

    /** 宏观循环区间终点 b（秒）：{@code loop_end}；负数 = 未声明（运行时取宏观末端） */
    private final float loopEnd;

    public Timeline(float totalDuration, List<TimelineTrack> tracks, float loopStart, float loopEnd) {
        this.totalDuration = totalDuration;
        this.tracks = tracks != null ? tracks : Collections.emptyList();
        this.loopStart = loopStart;
        this.loopEnd = loopEnd;
    }

    public float getTotalDuration() { return totalDuration; }
    public List<TimelineTrack> getTracks() { return tracks; }

    /**
     * 宏观循环区间起点 a（秒）。作为 {@code meta.macro_loop} 的区间参数；
     * 缺省 0，负值按 0 处理。仅在 {@code macro_loop=true} 时被读取。
     */
    public float getLoopStart() { return loopStart; }

    /**
     * 宏观循环区间终点 b（秒）。作为 {@code meta.macro_loop} 的区间参数；
     * 负数 = 未声明 → 运行时取宏观末端（各片段展开结束时刻的最大值）。
     */
    public float getLoopEnd() { return loopEnd; }

    /** 是否为无限时长时间轴 */
    public boolean isInfinite() { return totalDuration < 0f; }

    // ========== 便捷查询方法 ==========

    /**
     * 按轨道类型查找第一条匹配的轨道
     *
     * @param type 轨道类型
     * @return 匹配的轨道，可能为空
     */
    public Optional<TimelineTrack> findTrack(TrackType type) {
        return tracks.stream()
                .filter(t -> t.getType() == type)
                .findFirst();
    }

    /**
     * 获取 Camera 轨道（最多1条）
     *
     * @return Camera 轨道，可能为空
     */
    public Optional<TimelineTrack> getCameraTrack() {
        return findTrack(TrackType.CAMERA);
    }

    /**
     * 获取 Letterbox 轨道（最多1条）
     */
    public Optional<TimelineTrack> getLetterboxTrack() {
        return findTrack(TrackType.LETTERBOX);
    }

    /**
     * 获取所有 Audio 轨道
     */
    public List<TimelineTrack> getAudioTracks() {
        return tracks.stream()
                .filter(t -> t.getType() == TrackType.AUDIO)
                .toList();
    }

    /**
     * 获取 Event 轨道（最多1条）
     */
    public Optional<TimelineTrack> getEventTrack() {
        return findTrack(TrackType.EVENT);
    }

    /**
     * 获取所有 ModEvent 轨道
     */
    public List<TimelineTrack> getModEventTracks() {
        return tracks.stream()
                .filter(t -> t.getType() == TrackType.MOD_EVENT)
                .toList();
    }

    @Override
    public String toString() {
        return String.format("Timeline{duration=%.2f, tracks=%d}", totalDuration, tracks.size());
    }
}
