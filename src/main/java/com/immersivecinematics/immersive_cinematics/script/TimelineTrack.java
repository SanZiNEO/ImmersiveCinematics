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
     * @return 类型安全的 CameraClip 列表
     * @throws IllegalStateException 如果轨道类型不是 CAMERA
     */
    @SuppressWarnings("unchecked")
    public List<CameraClip> getCameraClips() {
        if (type != TrackType.CAMERA) {
            throw new IllegalStateException(
                "Cannot get CameraClips from track type " + type + "; expected CAMERA");
        }
        return (List<CameraClip>) clips;
    }

    /**
     * 获取 LetterboxClip 列表
     *
     * @throws IllegalStateException 如果轨道类型不是 LETTERBOX
     */
    @SuppressWarnings("unchecked")
    public List<LetterboxClip> getLetterboxClips() {
        if (type != TrackType.LETTERBOX) {
            throw new IllegalStateException(
                "Cannot get LetterboxClips from track type " + type + "; expected LETTERBOX");
        }
        return (List<LetterboxClip>) clips;
    }

    /**
     * 获取 AudioClip 列表
     *
     * @throws IllegalStateException 如果轨道类型不是 AUDIO
     */
    @SuppressWarnings("unchecked")
    public List<AudioClip> getAudioClips() {
        if (type != TrackType.AUDIO) {
            throw new IllegalStateException(
                "Cannot get AudioClips from track type " + type + "; expected AUDIO");
        }
        return (List<AudioClip>) clips;
    }

    /**
     * 获取 EventClip 列表
     *
     * @throws IllegalStateException 如果轨道类型不是 EVENT
     */
    @SuppressWarnings("unchecked")
    public List<EventClip> getEventClips() {
        if (type != TrackType.EVENT) {
            throw new IllegalStateException(
                "Cannot get EventClips from track type " + type + "; expected EVENT");
        }
        return (List<EventClip>) clips;
    }

    /**
     * 获取 ModEventClip 列表
     *
     * @throws IllegalStateException 如果轨道类型不是 MOD_EVENT
     */
    @SuppressWarnings("unchecked")
    public List<ModEventClip> getModEventClips() {
        if (type != TrackType.MOD_EVENT) {
            throw new IllegalStateException(
                "Cannot get ModEventClips from track type " + type + "; expected MOD_EVENT");
        }
        return (List<ModEventClip>) clips;
    }

    @Override
    public String toString() {
        return String.format("TimelineTrack{type=%s, clips=%d}", type, clips.size());
    }
}
