package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;

import java.util.List;

/**
 * 关键帧插值器 — 匀速插值模型
 * <p>
 * 关键帧之间为匀速直线运动。弧长进度 s 等于线性时间进度 t，无需速度曲线。
 * 贝塞尔路径通过 ArcLengthLUT 确保路径上的匀速运动。
 * <p>
 * 此类为无状态工具类，所有方法都是静态的，不持有任何运行时状态。
 */
public final class KeyframeInterpolator {

    private KeyframeInterpolator() {}

    // ========== 时间计算 ==========

    /**
     * 计算关键帧插值结果
     * <p>
     * 算法：
     * <ol>
     *   <li>根据 clipTime 找到关键帧段 (from, to)</li>
     *   <li>计算段内线性进度 t ∈ [0, 1]</li>
     *   <li>弧长进度 s = t（匀速）</li>
     *   <li>返回 from, to, s</li>
     * </ol>
     * <p>
     * 循环处理：如果 clip.loop=true，对片段内时间取模实现循环。
     *
     * @param clipTime 片段内时间（秒）
     * @param clip     所属片段
     * @return 插值结果，包含 from/to 关键帧和弧长进度 s；
     *         如果时间在关键帧范围外，返回 null
     */
    public static InterpolationResult computeInterpolation(float clipTime, Clip clip) {
        List<Keyframe> keyframes = clip.getKeyframes();
        if (keyframes == null || keyframes.isEmpty()) return null;

        float effectiveTime = clipTime;

        // 循环处理
        if (clip.isLoop() && keyframes.size() >= 2) {
            float animPeriod = clip.getAnimPeriod();
            if (animPeriod > 0) {
                if (clip.getLoopCount() > 0) {
                    float maxLoopTime = animPeriod * clip.getLoopCount();
                    if (effectiveTime >= maxLoopTime) {
                        Keyframe last = keyframes.get(keyframes.size() - 1);
                        Keyframe secondLast = keyframes.get(keyframes.size() - 2);
                        return new InterpolationResult(secondLast, last, 1.0f);
                    }
                }
                float offset = keyframes.get(0).getTime();
                if ("pingpong".equals(clip.getLoopMode())) {
                    // 往复折返：以 2×周期为模，超周期部分镜像（监控来回摇，端点速度反向、位置连续）
                    float t = (effectiveTime - offset) % (2f * animPeriod);
                    effectiveTime = offset + (t <= animPeriod ? t : 2f * animPeriod - t);
                } else {
                    // repeat：周期内从头到尾重复
                    effectiveTime = offset + (effectiveTime - offset) % animPeriod;
                }
            }
        }

        // 找到当前所处的两个关键帧
        Keyframe from = null;
        Keyframe to = null;
        int fromIndex = -1;

        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (effectiveTime >= keyframes.get(i).getTime() && effectiveTime <= keyframes.get(i + 1).getTime()) {
                from = keyframes.get(i);
                to = keyframes.get(i + 1);
                fromIndex = i;
                break;
            }
        }

        if (from == null || to == null) {
            if (effectiveTime <= keyframes.get(0).getTime()) {
                return new InterpolationResult(keyframes.get(0), keyframes.get(0), 0f);
            } else {
                Keyframe last = keyframes.get(keyframes.size() - 1);
                return new InterpolationResult(last, last, 1f);
            }
        }

        // 计算段内线性进度 t
        float fromTime = from.getTime();
        float toTime = to.getTime();
        float t;
        if (toTime == fromTime) {
            t = 1f;
        } else {
            t = (effectiveTime - fromTime) / (toTime - fromTime);
        }
        t = Math.max(0f, Math.min(1f, t));

        float s = t;

        return new InterpolationResult(from, to, s);
    }

    // ========== 标量通道插值 ==========

    /**
     * 标量通道插值（匀速线性；0.3.6 起运行时统一线性，缓动由编辑器烘焙成显式关键帧）。
     *
     * <p>取值语义与各轨道播放器里的逐通道取值同一口径（{@code OverlayTrackPlayer} 的
     * {@code opacity} / {@code x} / {@code y} / {@code scale_*} 就是这么取的）：</p>
     * <ul>
     *   <li>关键帧列表为空 → {@code defaultValue}；</li>
     *   <li>只有一个关键帧 → 该关键帧的值；</li>
     *   <li>时间在首帧之前 / 末帧之后 → 取边界关键帧的值（不外推）；</li>
     *   <li>段内 → 两端关键帧线性插值（零长段取 0 进度）。</li>
     * </ul>
     *
     * @param keyframes    片段的关键帧列表（时间递增）
     * @param localTime    片段内本地时间（秒）
     * @param key          通道名（关键帧 {@code data} 里的字段名）
     * @param defaultValue 关键帧没写该字段时的缺省值
     * @return 插值结果
     */
    public static float interpolateChannel(List<Keyframe> keyframes, float localTime, String key,
                                           float defaultValue) {
        if (keyframes == null || keyframes.isEmpty()) return defaultValue;
        if (keyframes.size() < 2) return keyframes.get(0).getFloat(key, defaultValue);

        int i = -1;
        for (int j = 0; j < keyframes.size() - 1; j++) {
            if (localTime >= keyframes.get(j).getTime() && localTime <= keyframes.get(j + 1).getTime()) {
                i = j;
                break;
            }
        }
        if (i < 0) {
            return localTime < keyframes.get(0).getTime()
                    ? keyframes.get(0).getFloat(key, defaultValue)
                    : keyframes.get(keyframes.size() - 1).getFloat(key, defaultValue);
        }

        Keyframe from = keyframes.get(i);
        Keyframe to = keyframes.get(i + 1);
        float span = to.getTime() - from.getTime();
        float t = (span > 0.001f) ? (localTime - from.getTime()) / span : 0f;
        t = Math.max(0f, Math.min(1f, t));
        return MathUtil.lerp(from.getFloat(key, defaultValue), to.getFloat(key, defaultValue), t);
    }

    // ========== 朝向插值 ==========

    /**
     * 在两个关键帧之间插值偏航角（角度环绕插值）
     */
    public static float interpolateYaw(Keyframe from, Keyframe to, float s) {
        float result = MathUtil.lerpAngle(from.getYaw(), to.getYaw(), s);
        return MathUtil.sanitizeFloat(result, from.getYaw());
    }

    /**
     * 在两个关键帧之间插值俯仰角（线性插值）
     */
    public static float interpolatePitch(Keyframe from, Keyframe to, float s) {
        float result = MathUtil.lerp(from.getPitch(), to.getPitch(), s);
        return MathUtil.sanitizeFloat(result, from.getPitch());
    }

    /**
     * 在两个关键帧之间插值滚转角（角度环绕插值）
     */
    public static float interpolateRoll(Keyframe from, Keyframe to, float s) {
        float result = MathUtil.lerpAngle(from.getRoll(), to.getRoll(), s);
        return MathUtil.sanitizeFloat(result, from.getRoll());
    }

    // ========== 光学插值 ==========

    public static float interpolateFov(Keyframe from, Keyframe to, float s) {
        float result = MathUtil.lerp(from.getFov(), to.getFov(), s);
        return MathUtil.sanitizeFloat(result, from.getFov());
    }

    public static float interpolateZoom(Keyframe from, Keyframe to, float s) {
        float a = from.getZoom();
        float b = to.getZoom();
        float result;
        if (a > 0f && b > 0f) {
            // 对数插值：倍率变化在视觉上均匀（1→100 的中点 = 10，而不是 50.5）
            result = (float) Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * s);
        } else {
            result = MathUtil.lerp(a, b, s);
        }
        return MathUtil.sanitizeFloat(result, a);
    }


    // ========== 结果容器 ==========

    /**
     * 插值计算结果 — 包含 from/to 关键帧和弧长进度 s
     */
    public static class InterpolationResult {
        /** 起始关键帧 */
        public final Keyframe from;
        /** 目标关键帧 */
        public final Keyframe to;
        /** 弧长进度 s [0, 1]（匀速模型下 s = t） */
        public final float adjustedT;

        public InterpolationResult(Keyframe from, Keyframe to, float adjustedT) {
            this.from = from;
            this.to = to;
            this.adjustedT = adjustedT;
        }
    }
}
