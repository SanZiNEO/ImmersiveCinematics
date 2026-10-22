package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;

/**
 * 时间轴轨道 —— 同类型的片段数组；片段统一用 {@link Clip} 容器，轨道特有字段在 {@code Clip.data} 中，
 * 结构由 {@link SchemaLoader} 定义。
 */
public class TimelineTrack {

    private final TrackType type;
    private final List<Clip> clips;

    /**
     * @param type  轨道类型
     * @param clips 该轨道的片段列表（按时间轴顺序）
     */
    public TimelineTrack(TrackType type, List<Clip> clips) {
        this.type = type;
        this.clips = clips;
    }

    /** 轨道类型。 */
    public TrackType getType() { return type; }

    /** 获取通用片段列表。 */
    public List<Clip> getClips() { return clips; }

    /**
     * 片段数量。
     *
     * @return 片段数
     */
    public int getClipCount() { return clips.size(); }

    @Override
    public String toString() {
        return String.format("TimelineTrack{type=%s, clips=%d}", type, clips.size());
    }
}
