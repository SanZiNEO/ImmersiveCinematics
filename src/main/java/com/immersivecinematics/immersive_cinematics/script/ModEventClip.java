package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.Map;

/**
 * 模组事件片段 — 第三方模组扩展事件
 * <p>
 * mod_event 轨道不限制数量，不同模组各自一条轨道。
 * data 字段的结构完全由模组定义，本模组只负责按时间戳分发。
 */
public class ModEventClip {

    /** 总时间轴上的开始时间（秒） */
    private final float startTime;

    /** 持续时长（秒） */
    private final float duration;

    /** 模组命名空间事件类型（如 "mymod:animation"） */
    private final String eventType;

    /** 模组自定义数据，结构由模组定义 */
    private final Map<String, Object> data;

    public ModEventClip(float startTime, float duration, String eventType, Map<String, Object> data) {
        this.startTime = startTime;
        this.duration = duration;
        this.eventType = eventType;
        this.data = data != null ? data : Collections.emptyMap();
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getData() { return data; }

    @Override
    public String toString() {
        return String.format("ModEventClip{start=%.2f, dur=%.2f, type=%s, data=%s}",
                startTime, duration, eventType, data);
    }
}
