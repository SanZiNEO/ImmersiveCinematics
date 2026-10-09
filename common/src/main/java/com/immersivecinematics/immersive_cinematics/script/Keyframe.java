package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;

import java.util.Collections;
import java.util.Map;

/**
 * 通用关键帧容器 — 替代各轨道类型的独立 Keyframe 类
 * <p>
 * 所有关键帧共享 {@code time} 字段。
 * 轨道特有字段存储在 {@code data} map 中。
 */
public class Keyframe {

    private final float time;
    private final TrackType trackType;
    private final Map<String, Object> data;

    public Keyframe(float time, TrackType trackType, Map<String, Object> data) {
        this.time = time;
        this.trackType = trackType;
        this.data = data != null ? data : Collections.emptyMap();
    }

    // ── 通用字段 ──

    public float getTime() { return time; }
    public TrackType getTrackType() { return trackType; }
    public Map<String, Object> getData() { return data; }

    // ── 泛型字段访问 ──

    public String getString(String key, String defaultValue) {
        Object v = data.get(key);
        return v instanceof String ? (String) v : defaultValue;
    }

    public float getFloat(String key, float defaultValue) {
        Object v = data.get(key);
        if (!(v instanceof Number)) return defaultValue;
        // 数据入口守卫：非有限值（NaN / ±Infinity）回落缺省（标量通道缺省 0 = 无效果）——
        // 脚本 JSON 的 NaN / Infinity 字面量或溢出数字（1e999）不得直达采样器 / shader
        return MathUtil.sanitizeFloatLogged(((Number) v).floatValue(), defaultValue, "keyframe." + key);
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

    // ── Convenience: Camera keyframes ──

    public PositionData getPosition() {
        return getObject("position");
    }

    public float getYaw() { return getFloat("yaw", 0f); }
    public float getPitch() { return getFloat("pitch", 0f); }
    public float getRoll() { return getFloat("roll", 0f); }
    public float getFov() { return getFloat("fov", 70f); }
    public float getZoom() { return getFloat("zoom", 1.0f); }

    // ── Convenience: Letterbox keyframes ──

    public float getAspectRatio() { return getFloat("aspect_ratio", 2.35f); }

    @Override
    public String toString() {
        return String.format("Keyframe{time=%.2f, data=%s}", time, data);
    }
}
