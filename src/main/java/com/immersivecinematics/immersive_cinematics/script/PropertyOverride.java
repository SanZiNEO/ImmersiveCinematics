package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;

/**
 * 属性级速度覆盖 — 为某个属性单独定义速度曲线
 * <p>
 * 当某个属性需要独立于片段默认速度曲线时使用。
 * 例如：位置匀速但 FOV 缓入。
 * <p>
 * JSON 示例：
 * <pre>
 * {
 *   "interpolation": "linear",
 *   "keyframes": [
 *     { "time": 0, "speed": 0.3, "curve_bias": 0.0 },
 *     { "time": 6, "speed": 1.0, "curve_bias": 0.0 }
 *   ]
 * }
 * </pre>
 * <p>
 * 支持的属性名：position, yaw, pitch, roll, fov, zoom, dof
 */
public class PropertyOverride {

    private final InterpolationType interpolation;

    private final List<SpeedKeyframe> keyframes;

    public PropertyOverride(InterpolationType interpolation, List<SpeedKeyframe> keyframes) {
        this.interpolation = interpolation;
        this.keyframes = keyframes != null ? keyframes : Collections.emptyList();
    }

    public InterpolationType getInterpolation() { return interpolation; }

    public List<SpeedKeyframe> getKeyframes() { return keyframes; }

    /**
     * 属性级速度关键帧 — 只包含时间、速度和弯曲控制
     */
    public static class SpeedKeyframe {
        private final float time;
        private final float speed;
        private final float curveBias;

        public SpeedKeyframe(float time, float speed, float curveBias) {
            this.time = time;
            this.speed = speed;
            this.curveBias = curveBias;
        }

        public float getTime() { return time; }
        public float getSpeed() { return speed; }
        public float getCurveBias() { return curveBias; }
    }
}
