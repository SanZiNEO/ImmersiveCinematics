package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通用片段容器 — 替代各轨道类型的独立 Clip 类
 * <p>
 * 所有轨道共享 {@code startTime} 和 {@code duration} 字段。
 * 轨道特有字段存储在 {@code data} map 中，通过 {@link SchemaLoader} 定义。
 * 关键帧使用 {@link Keyframe} 泛型类。
 */
public class Clip {

    private final float startTime;
    private final float duration;
    private final TrackType trackType;
    private final Map<String, Object> data;
    private final List<Keyframe> keyframes;

    public Clip(float startTime, float duration, TrackType trackType,
                Map<String, Object> data, List<Keyframe> keyframes) {
        this.startTime = startTime;
        this.duration = duration;
        this.trackType = trackType;
        this.data = data != null ? data : Collections.emptyMap();
        this.keyframes = keyframes != null ? keyframes : Collections.emptyList();
    }

    // ── 通用字段 ──

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public TrackType getTrackType() { return trackType; }
    public Map<String, Object> getData() { return data; }
    public List<Keyframe> getKeyframes() { return keyframes; }

    /** 是否为无限时长片段（负数即视为无限时长） */
    public boolean isInfinite() { return duration < 0f; }

    // ── 泛型字段访问 ──

    public String getString(String key, String defaultValue) {
        Object v = data.get(key);
        return v instanceof String ? (String) v : defaultValue;
    }

    public float getFloat(String key, float defaultValue) {
        Object v = data.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object v = data.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultValue;
    }

    public boolean getBool(String key, boolean defaultValue) {
        Object v = data.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        return (T) data.get(key);
    }

    // ── Convenience: CAMERA ──

    public boolean isMorph() {
        return "morph".equals(getString("transition", "cut"));
    }

    public float getTransitionDuration() {
        return getFloat("transition_duration", 0.5f);
    }

    public boolean isPositionModeRelative() {
        return "relative".equals(getString("position_mode", "relative"));
    }

    public boolean isLoop() {
        return getBool("loop", false);
    }

    public int getLoopCount() {
        return getInt("loop_count", -1);
    }

    /**
     * 循环时间映射模式：repeat=周期内从头到尾重复；pingpong=往复折返。
     * 仅 loop=true 时生效。
     */
    public String getLoopMode() {
        return getString("loop_mode", "repeat");
    }

    /** 单次循环周期 = 末关键帧时间 - 首关键帧时间（关键帧不足 2 个时为 0） */
    public float getAnimPeriod() {
        if (keyframes.size() < 2) return 0f;
        return keyframes.get(keyframes.size() - 1).getTime() - keyframes.get(0).getTime();
    }

    /**
     * 是否为无限循环（loop=true 且 loop_count=-1）。
     * 注：loop_count=0 在解析期已被修正为 1（有限循环），不会到达这里。
     */
    public boolean isLoopInfinite() {
        return isLoop() && getLoopCount() < 0;
    }

    /**
     * 片段是否"永不结束"的统一判断：
     * duration 为负（无限时长片段）或无限循环（loop=true + loop_count=-1）。
     * 所有"这个片段会不会结束"的判定都应走此方法。
     */
    public boolean isEffectivelyInfinite() {
        return isInfinite() || isLoopInfinite();
    }

    /**
     * 片段活跃窗口末端（不含）：
     * 无限（时长或循环）→ Float.MAX_VALUE；
     * 有限循环 → start + 周期 × 次数；
     * 普通 → start + duration。
     * 与 KeyframeInterpolator 的循环钳制共用同一窗口公式。
     */
    public float getWindowEnd() {
        if (isEffectivelyInfinite()) return Float.MAX_VALUE;
        if (isLoop() && getLoopCount() > 0) {
            float animPeriod = getAnimPeriod();
            if (animPeriod > 0) return startTime + animPeriod * getLoopCount();
        }
        return startTime + duration;
    }

    public BezierCurve getCurve() {
        return getObject("curve");
    }

    // ── Convenience: AUDIO ──

    public String getSound() {
        return getString("sound", "");
    }

    public float getVolume() {
        return getFloat("volume", 1.0f);
    }

    public float getAudioPitch() {
        return getFloat("pitch", 1.0f);
    }

    public float getFadeIn() {
        return getFloat("fade_in", 0f);
    }

    public float getFadeOut() {
        return getFloat("fade_out", 0f);
    }

    public String getSource() {
        return getString("source", "file");
    }

    public String getAttenuation() {
        return getString("attenuation", "linear");
    }

    public String getAudioPositionMode() {
        return getString("position_mode", "relative");
    }


    // ── Convenience: EVENT ──

    public String getEventType() {
        return getString("event_type", "");
    }

    public String getCommand() {
        return getString("command", "");
    }

    /** 是否为瞬发事件（duration=0） */
    public boolean isInstant() { return duration == 0f; }

    @Override
    public String toString() {
        return String.format("Clip{type=%s, start=%.2f, dur=%.2f, data=%s, kfs=%d}",
                trackType, startTime, duration, data, keyframes.size());
    }
}
