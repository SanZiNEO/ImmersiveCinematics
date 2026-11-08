package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 时间轴 —— 脚本的核心数据容器：并行轨道；层级 = 时间轴 → 轨道 → 片段 → 关键帧。
 * 无总时长字段：有限末端 / 无限性由片段窗口在 {@link CinematicScript} 上派生。
 */
public class Timeline {

    /** 并行轨道列表（构造后不再修改） */
    private final List<TimelineTrack> tracks;

    /** 宏观循环区间起点 a（秒）：{@code loop_start}，缺省 0（负值按 0 处理）：float 存储（脚本 JSON 精度）；运行时读取后按 double 参与计算，写回时显式窄化。 */
    private final float loopStart;

    /** 宏观循环区间终点 b（秒）：{@code loop_end}；负数 = 未声明（运行时取宏观末端）：float 存储（脚本 JSON 精度）；运行时读取后按 double 参与计算，写回时显式窄化。 */
    private final float loopEnd;

    /**
     * @param tracks    并行轨道（null 视为空）
     * @param loopStart 宏观循环区间起点（秒，负值按 0 处理）
     * @param loopEnd   宏观循环区间终点（秒）；负数 = 未声明
     */
    public Timeline(List<TimelineTrack> tracks, float loopStart, float loopEnd) {
        this.tracks = tracks != null ? tracks : Collections.emptyList();
        this.loopStart = loopStart;
        this.loopEnd = loopEnd;
    }

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

    // ========== 便捷查询方法 ==========

    /**
     * 按轨道类型查找第一条匹配的轨道。
     *
     * @param type 轨道类型
     * @return 匹配的轨道，可能为空
     */
    public Optional<TimelineTrack> findTrack(TrackTypeId type) {
        return tracks.stream()
                .filter(t -> type.equals(t.getType()))
                .findFirst();
    }

    /**
     * 获取 Camera 轨道（最多1条）
     *
     * @return Camera 轨道，可能为空
     */
    public Optional<TimelineTrack> getCameraTrack() {
        return findTrack(TrackTypeId.CAMERA);
    }

    @Override
    public String toString() {
        return String.format("Timeline{tracks=%d}", tracks.size());
    }
}
