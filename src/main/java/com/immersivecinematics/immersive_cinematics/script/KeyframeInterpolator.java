package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

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
    public static InterpolationResult computeInterpolation(float clipTime, CameraClip clip) {
        List<CameraKeyframe> keyframes = clip.getKeyframes();
        if (keyframes == null || keyframes.isEmpty()) return null;

        float effectiveTime = clipTime;

        // 循环处理
        if (clip.isLoop() && keyframes.size() >= 2) {
            float animPeriod = keyframes.get(keyframes.size() - 1).getTime() - keyframes.get(0).getTime();
            if (animPeriod > 0) {
                if (clip.getLoopCount() > 0) {
                    float maxLoopTime = animPeriod * clip.getLoopCount();
                    if (effectiveTime >= maxLoopTime) {
                        CameraKeyframe last = keyframes.get(keyframes.size() - 1);
                        CameraKeyframe secondLast = keyframes.get(keyframes.size() - 2);
                        return new InterpolationResult(secondLast, last, 1.0f);
                    }
                }
                float offset = keyframes.get(0).getTime();
                effectiveTime = offset + (effectiveTime - offset) % animPeriod;
            }
        }

        // 找到当前所处的两个关键帧
        CameraKeyframe from = null;
        CameraKeyframe to = null;
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
                CameraKeyframe last = keyframes.get(keyframes.size() - 1);
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

    // ========== 位置插值 ==========

    /**
     * 在两个关键帧之间插值位置
     * <p>
     * 弧长进度 s 通过 PathStrategy 映射为实际位置。
     * 贝塞尔路径策略内部使用 ArcLengthLUT 确保匀速运动。
     *
     * @param from 起始关键帧
     * @param to   目标关键帧
     * @param s    弧长进度 [0, 1]
     * @param clip 所属片段（用于获取贝塞尔曲线和位置模式）
     * @return 插值后的位置
     */
    public static Vec3 interpolatePosition(CameraKeyframe from, CameraKeyframe to, float s, CameraClip clip) {
        Vec3 p0 = from.getPosition().toVec3();
        Vec3 p3 = to.getPosition().toVec3();

        String curveType = (clip.getCurve() != null) ? clip.getCurve().getType() : null;
        PathStrategy strategy = PathStrategies.get(curveType);
        Vec3 result = strategy.interpolate(p0, p3, s, clip.getCurve());

        return MathUtil.sanitizeVec3(result, p0);
    }

    // ========== 朝向插值 ==========

    /**
     * 在两个关键帧之间插值偏航角（角度环绕插值）
     */
    public static float interpolateYaw(CameraKeyframe from, CameraKeyframe to, float s) {
        float result = MathUtil.lerpAngle(from.getYaw(), to.getYaw(), s);
        return MathUtil.sanitizeFloat(result, from.getYaw());
    }

    /**
     * 在两个关键帧之间插值俯仰角（线性插值）
     */
    public static float interpolatePitch(CameraKeyframe from, CameraKeyframe to, float s) {
        float result = MathUtil.lerp(from.getPitch(), to.getPitch(), s);
        return MathUtil.sanitizeFloat(result, from.getPitch());
    }

    /**
     * 在两个关键帧之间插值滚转角（角度环绕插值）
     */
    public static float interpolateRoll(CameraKeyframe from, CameraKeyframe to, float s) {
        float result = MathUtil.lerpAngle(from.getRoll(), to.getRoll(), s);
        return MathUtil.sanitizeFloat(result, from.getRoll());
    }

    // ========== 光学插值 ==========

    public static float interpolateFov(CameraKeyframe from, CameraKeyframe to, float s) {
        float result = MathUtil.lerp(from.getFov(), to.getFov(), s);
        return MathUtil.sanitizeFloat(result, from.getFov());
    }

    public static float interpolateZoom(CameraKeyframe from, CameraKeyframe to, float s) {
        float result = MathUtil.lerp(from.getZoom(), to.getZoom(), s);
        return MathUtil.sanitizeFloat(result, from.getZoom());
    }

    public static float interpolateDof(CameraKeyframe from, CameraKeyframe to, float s) {
        float result = MathUtil.lerp(from.getDof(), to.getDof(), s);
        return MathUtil.sanitizeFloat(result, from.getDof());
    }

    // ========== 结果容器 ==========

    /**
     * 插值计算结果 — 包含 from/to 关键帧和弧长进度 s
     */
    public static class InterpolationResult {
        /** 起始关键帧 */
        public final CameraKeyframe from;
        /** 目标关键帧 */
        public final CameraKeyframe to;
        /** 弧长进度 s [0, 1]（匀速模型下 s = t） */
        public final float adjustedT;

        public InterpolationResult(CameraKeyframe from, CameraKeyframe to, float adjustedT) {
            this.from = from;
            this.to = to;
            this.adjustedT = adjustedT;
        }
    }
}
