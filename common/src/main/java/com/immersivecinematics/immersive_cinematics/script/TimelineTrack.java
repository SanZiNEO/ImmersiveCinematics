package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;

/**
 * 时间轴轨道 — 包含同类型的通用片段数组
 * <p>
 * 所有片段使用统一的 {@link Clip} 容器，轨道特有字段存储在 Clip.data 中。
 * 通过 {@link SchemaLoader} 定义字段结构和默认值。
 */
public class TimelineTrack {

    private final TrackType type;
    private final List<Clip> clips;

    public TimelineTrack(TrackType type, List<Clip> clips) {
        this.type = type;
        this.clips = clips;
    }

    public TrackType getType() { return type; }

    /** 获取通用片段列表 */
    public List<Clip> getClips() { return clips; }

    /** 片段数量 */
    public int getClipCount() { return clips.size(); }

    @Override
    public String toString() {
        return String.format("TimelineTrack{type=%s, clips=%d}", type, clips.size());
    }
}
