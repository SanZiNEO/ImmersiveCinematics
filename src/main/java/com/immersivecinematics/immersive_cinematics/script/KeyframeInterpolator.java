package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 关键帧插值器 — 组合调用 MathUtil 实现关键帧间的属性插值
 * <p>
 * 职责：
 * <ul>
 *   <li>根据 InterpolationType 将线性进度 t 映射为 adjustedT</li>
 *   <li>支持两种插值作用域（InterpolationScope）：
 *       <ul>
 *         <li>CLIP — 曲线映射应用到整体 clip 进度，关键帧段内线性</li>
 *         <li>SEGMENT — 曲线映射应用到每个关键帧段（传统行为）</li>
 *       </ul>
 *   </li>
 *   <li>支持逐关键帧插值覆盖（CameraKeyframe.interpolation）</li>
 *   <li>位置插值：直线 lerp 或贝塞尔曲线</li>
 *   <li>朝向插值：yaw/roll 使用角度环绕插值，pitch 使用线性插值</li>
 *   <li>光学插值：fov/zoom/dof 使用线性插值</li>
 * </ul>
 * <p>
 * 此类为无状态工具类，所有方法都是静态的，不持有任何运行时状态。
 * 运行时状态由 ScriptPlayer/TrackPlayer 管理。
 */
public final class KeyframeInterpolator {

    private KeyframeInterpolator() {} // 禁止实例化

    // ========== 插值曲线映射 ==========

    /**
     * 根据插值类型将线性进度 t 映射为 adjustedT
     *
     * @param t             线性进度 [0, 1]
     * @param interpolation 插值类型
     * @return 映射后的进度 [0, 1]
     */
    public static float applyCurve(float t, InterpolationType interpolation) {
        return switch (interpolation) {
            case LINEAR -> t;
            case SMOOTH -> MathUtil.smooth(t);
            case EASE_IN -> MathUtil.easeIn(t);
            case EASE_OUT -> MathUtil.easeOut(t);
            case EASE_IN_OUT -> MathUtil.easeInOut(t);
        };
    }

    /**
     * 数学组合插值 — 先应用脚本级曲线，再应用片段/关键帧级曲线
     * <p>
     * 三层插值体系（数学组合模式）：
     * <pre>
     * adjustedT = applyCurve(applyCurve(t, scriptInterp), clipOrKeyframeInterp)
     * </pre>
     * 脚本级 LINEAR = 恒等函数，不施加全局曲线，片段级单独生效。
     * <p>
     * 示例：脚本级 smooth + 片段级 ease_in =
     * adjustedT = ease_in(smooth(t))，整体缓入缓出 + 局部缓入。
     *
     * @param t                      线性进度 [0, 1]
     * @param scriptInterpolation    脚本级插值（全局默认风格），null=LINEAR
     * @param clipOrKeyframeInterp   片段级或关键帧级插值
     * @return 组合映射后的进度 [0, 1]
     */
    public static float applyComposedCurve(float t,
                                           @Nullable InterpolationType scriptInterpolation,
                                           InterpolationType clipOrKeyframeInterp) {
        // 先应用脚本级曲线
        InterpolationType effectiveScript = scriptInterpolation != null
                ? scriptInterpolation : InterpolationType.LINEAR;
        float t1 = applyCurve(t, effectiveScript);
        // 再应用片段/关键帧级曲线
        float t2 = applyCurve(t1, clipOrKeyframeInterp);
        return t2;
    }

    // ========== 位置插值 ==========

    /**
     * 在两个关键帧之间插值位置
     * <p>
     * 如果 clip 有贝塞尔曲线，使用三次贝塞尔插值；
     * 否则使用直线 lerp。
     *
     * @param from  起始关键帧
     * @param to    目标关键帧
     * @param t     映射后的进度 adjustedT [0, 1]
     * @param clip  所属片段（用于获取贝塞尔曲线）
     * @return 插值后的位置
     */
    public static Vec3 interpolatePosition(CameraKeyframe from, CameraKeyframe to, float t, CameraClip clip) {
        Vec3 p0 = from.getPosition().toVec3();
        Vec3 p3 = to.getPosition().toVec3();

        if (clip.getCurve() != null && clip.getCurve().isValid()) {
            // 三次贝塞尔曲线插值
            Vec3 p1 = clip.getCurve().getP1();
            Vec3 p2 = clip.getCurve().getP2();
            return MathUtil.cubicBezier(p0, p1, p2, p3, t);
        } else {
            // 直线插值
            return p0.lerp(p3, t);
        }
    }

    // ========== 朝向插值 ==========

    /**
     * 在两个关键帧之间插值偏航角（角度环绕插值）
     */
    public static float interpolateYaw(CameraKeyframe from, CameraKeyframe to, float t) {
        return MathUtil.lerpAngle(from.getYaw(), to.getYaw(), t);
    }

    /**
     * 在两个关键帧之间插值俯仰角（线性插值）
     */
    public static float interpolatePitch(CameraKeyframe from, CameraKeyframe to, float t) {
        return MathUtil.lerp(from.getPitch(), to.getPitch(), t);
    }

    /**
     * 在两个关键帧之间插值滚转角（角度环绕插值）
     */
    public static float interpolateRoll(CameraKeyframe from, CameraKeyframe to, float t) {
        return MathUtil.lerpAngle(from.getRoll(), to.getRoll(), t);
    }

    // ========== 光学插值 ==========

    public static float interpolateFov(CameraKeyframe from, CameraKeyframe to, float t) {
        return MathUtil.lerp(from.getFov(), to.getFov(), t);
    }

    public static float interpolateZoom(CameraKeyframe from, CameraKeyframe to, float t) {
        return MathUtil.lerp(from.getZoom(), to.getZoom(), t);
    }

    public static float interpolateDof(CameraKeyframe from, CameraKeyframe to, float t) {
        return MathUtil.lerp(from.getDof(), to.getDof(), t);
    }

    // ========== 时间计算 ==========

    /**
     * 计算片段内的关键帧进度（三层插值 — 数学组合模式）
     * <p>
     * 三层插值体系：
     * <pre>
     * adjustedT = applyCurve(applyCurve(t, scriptInterp), clipOrKeyframeInterp)
     * </pre>
     * <ul>
     *   <li>脚本级（scriptInterpolation）— 全局默认曲线，最先应用</li>
     *   <li>片段/关键帧级 — 根据 {@link InterpolationScope} 分两条路径：
     *     <ul>
     *       <li>{@link InterpolationScope#CLIP} — 曲线映射应用到整体 clip 进度，
     *           然后在调整后的时间轴上找关键帧段并线性插值。
     *           整个 clip 只在开头/结尾有缓入缓出，中间关键帧处速度连续。</li>
     *       <li>{@link InterpolationScope#SEGMENT} — 先找关键帧段，计算段内线性进度，
     *           再对段内进度应用曲线映射。支持逐关键帧插值覆盖。</li>
     *     </ul>
     *   </li>
     * </ul>
     * <p>
     * 循环处理：如果 clip.loop=true，对片段内时间取模实现循环。
     *
     * @param clipTime            片段内时间（秒）
     * @param clip                所属片段
     * @param scriptInterpolation 脚本级插值（全局默认风格），null=LINEAR（不施加全局曲线）
     * @return 插值结果，包含 from/to 关键帧和 adjustedT；
     *         如果时间在关键帧范围外，返回 null
     */
    public static InterpolationResult computeInterpolation(float clipTime, CameraClip clip,
                                                           @Nullable InterpolationType scriptInterpolation) {
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
                        return new InterpolationResult(
                                keyframes.get(keyframes.size() - 2),
                                keyframes.get(keyframes.size() - 1),
                                1.0f);
                    }
                }
                float offset = keyframes.get(0).getTime();
                effectiveTime = offset + (effectiveTime - offset) % animPeriod;
            }
        }

        // 根据插值作用域分两条路径
        if (clip.getInterpolationScope() == InterpolationScope.CLIP) {
            return computeClipScope(effectiveTime, clip, keyframes, scriptInterpolation);
        } else {
            return computeSegmentScope(effectiveTime, clip, keyframes, scriptInterpolation);
        }
    }

    /**
     * CLIP scope — 曲线映射应用到整体 clip 进度（数学组合模式）
     * <p>
     * 算法：
     * 1. 计算整体进度 overallT = (effectiveTime - firstTime) / (lastTime - firstTime)
     * 2. 应用组合曲线映射 adjustedOverallT = applyComposedCurve(overallT, scriptInterp, clip.interpolation)
     * 3. 映射回时间轴 adjustedTime = firstTime + adjustedOverallT * overallDuration
     * 4. 在 adjustedTime 上找关键帧段 + 线性插值（不再 applyCurve）
     */
    private static InterpolationResult computeClipScope(float effectiveTime, CameraClip clip,
                                                        List<CameraKeyframe> keyframes,
                                                        @Nullable InterpolationType scriptInterpolation) {
        float firstTime = keyframes.get(0).getTime();
        float lastTime = keyframes.get(keyframes.size() - 1).getTime();
        float overallDuration = lastTime - firstTime;

        if (overallDuration <= 0) {
            CameraKeyframe last = keyframes.get(keyframes.size() - 1);
            return new InterpolationResult(last, last, 1f);
        }

        // 1. 计算整体进度
        float overallT = (effectiveTime - firstTime) / overallDuration;
        overallT = Math.max(0f, Math.min(1f, overallT));

        // 2. 应用组合曲线映射到整体进度（先脚本级，再片段级）
        float adjustedOverallT = applyComposedCurve(overallT, scriptInterpolation, clip.getInterpolation());

        // 3. 映射回时间轴
        float adjustedTime = firstTime + adjustedOverallT * overallDuration;

        // 4. 在 adjustedTime 上找关键帧段 + 线性插值
        CameraKeyframe from = null;
        CameraKeyframe to = null;

        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (adjustedTime >= keyframes.get(i).getTime() && adjustedTime <= keyframes.get(i + 1).getTime()) {
                from = keyframes.get(i);
                to = keyframes.get(i + 1);
                break;
            }
        }

        if (from == null || to == null) {
            if (adjustedTime <= keyframes.get(0).getTime()) {
                return new InterpolationResult(keyframes.get(0), keyframes.get(0), 0f);
            } else {
                CameraKeyframe last = keyframes.get(keyframes.size() - 1);
                return new InterpolationResult(last, last, 1f);
            }
        }

        // 段内线性插值（不再 applyCurve，因为曲线映射已在整体进度上应用）
        float t;
        float fromTime = from.getTime();
        float toTime = to.getTime();
        if (toTime == fromTime) {
            t = 1f;
        } else {
            t = (adjustedTime - fromTime) / (toTime - fromTime);
        }
        t = Math.max(0f, Math.min(1f, t));

        return new InterpolationResult(from, to, t);
    }

    /**
     * SEGMENT scope — 曲线映射应用到每个关键帧段（数学组合模式）
     * <p>
     * 算法：
     * 1. 找到当前所处的关键帧段
     * 2. 计算段内线性进度 t
     * 3. 确定该段的插值类型（优先使用 to 关键帧的覆盖，否则用 clip 默认）
     * 4. 对段内进度应用组合曲线映射（先脚本级，再关键帧/片段级）
     */
    private static InterpolationResult computeSegmentScope(float effectiveTime, CameraClip clip,
                                                           List<CameraKeyframe> keyframes,
                                                           @Nullable InterpolationType scriptInterpolation) {
        // 找到当前所处的两个关键帧
        CameraKeyframe from = null;
        CameraKeyframe to = null;

        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (effectiveTime >= keyframes.get(i).getTime() && effectiveTime <= keyframes.get(i + 1).getTime()) {
                from = keyframes.get(i);
                to = keyframes.get(i + 1);
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
        float t;
        float fromTime = from.getTime();
        float toTime = to.getTime();
        if (toTime == fromTime) {
            t = 1f;
        } else {
            t = (effectiveTime - fromTime) / (toTime - fromTime);
        }
        t = Math.max(0f, Math.min(1f, t));

        // 确定该段的插值类型：优先使用 to 关键帧的覆盖
        InterpolationType segmentInterpolation = to.getInterpolation() != null
                ? to.getInterpolation()
                : clip.getInterpolation();

        // 应用组合插值曲线映射（先脚本级，再关键帧/片段级）
        float adjustedT = applyComposedCurve(t, scriptInterpolation, segmentInterpolation);

        return new InterpolationResult(from, to, adjustedT);
    }

    // ========== 结果容器 ==========

    /**
     * 插值计算结果 — 包含 from/to 关键帧和映射后的进度
     */
    public static class InterpolationResult {
        /** 起始关键帧 */
        public final CameraKeyframe from;
        /** 目标关键帧 */
        public final CameraKeyframe to;
        /** 映射后的进度 adjustedT [0, 1] */
        public final float adjustedT;

        public InterpolationResult(CameraKeyframe from, CameraKeyframe to, float adjustedT) {
            this.from = from;
            this.to = to;
            this.adjustedT = adjustedT;
        }
    }
}
