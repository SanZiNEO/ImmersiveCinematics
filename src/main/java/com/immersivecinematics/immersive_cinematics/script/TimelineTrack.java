package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;

/**
 * 时间轴轨道 — 包含同类型的片段数组
 * <p>
 * clips 是多态数组，根据 track.type 决定将每个 clip 解析为哪种 Clip 子类：
 * <ul>
 *   <li>CAMERA → List<CameraClip></li>
 *   <li>LETTERBOX → List<LetterboxClip></li>
 *   <li>AUDIO → List<AudioClip></li>
 *   <li>EVENT → List<EventClip></li>
 *   <li>MOD_EVENT → List<ModEventClip></li>
 * </ul>
 * <p>
 * Gson 反序列化策略：需要自定义 JsonDeserializer，根据 track.type 决定
 * 将每个 clip JSON 对象解析为哪种 Clip 子类。
 * <p>
 * 本类使用通配符 List<?> 存储原始列表，提供类型安全的便捷访问方法。
 */
public class TimelineTrack {

    /** 轨道类型 */
    private final TrackType type;

    /** 片段数组（多态） */
    private final List<?> clips;

    @SuppressWarnings("unchecked")
    public TimelineTrack(TrackType type, List<?> clips) {
        this.type = type;
        this.clips = clips != null ? clips : Collections.emptyList();
    }

    public TrackType getType() { return type; }

    /** 获取原始片段列表（通配符类型） */
    public List<?> getClips() { return clips; }

    /** 片段数量 */
    public int getClipCount() { return clips.size(); }

    // ========== 类型安全的便捷访问方法 ==========

    /**
     * 获取 CameraClip 列表
     *
     * @return 类型安全的 CameraClip 列表，如果轨道类型不匹配则抛出 ClassCastException
     */
    @SuppressWarnings("unchecked")
    public List<CameraClip> getCameraClips() {
        return (List<CameraClip>) clips;
    }

    /**
     * 获取 LetterboxClip 列表
     */
    @SuppressWarnings("unchecked")
    public List<LetterboxClip> getLetterboxClips() {
        return (List<LetterboxClip>) clips;
    }

    /**
     * 获取 AudioClip 列表
     */
    @SuppressWarnings("unchecked")
    public List<AudioClip> getAudioClips() {
        return (List<AudioClip>) clips;
    }

    /**
     * 获取 EventClip 列表
     */
    @SuppressWarnings("unchecked")
    public List<EventClip> getEventClips() {
        return (List<EventClip>) clips;
    }

    /**
     * 获取 ModEventClip 列表
     */
    @SuppressWarnings("unchecked")
    public List<ModEventClip> getModEventClips() {
        return (List<ModEventClip>) clips;
    }

    @Override
    public String toString() {
        return String.format("TimelineTrack{type=%s, clips=%d}", type, clips.size());
    }
}
