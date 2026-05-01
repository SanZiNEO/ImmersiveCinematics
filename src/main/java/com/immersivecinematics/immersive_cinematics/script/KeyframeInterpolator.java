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
 *   <li>支持两种曲线组合模式（CurveCompositionMode）：
 *       <ul>
 *         <li>OVERRIDE — 覆盖模式（默认）：片段/关键帧级覆盖脚本级默认值</li>
 *         <li>COMPOSED — 数学组合模式：先脚本级，再片段/关键帧级（双重平滑）</li>
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
     * 覆盖模式插值 — 片段/关键帧级覆盖脚本级默认值
     * <p>
     * 覆盖模式语义：
     * <ul>
     *   <li>片段/关键帧级已指定非 LINEAR → 使用它（覆盖脚本级）</li>
     *   <li>片段/关键帧级为 LINEAR 且脚本级非 null → 使用脚本级</li>
     *   <li>否则 → LINEAR（恒等）</li>
     * </ul>
     * 这符合用户心智模型：meta.interpolation 是"片段级未指定时的默认值"，
     * 而非"全局叠加曲线"。
     *
     * @param t                      线性进度 [0, 1]
     * @param scriptInterpolation    脚本级插值（全局默认风格），null=LINEAR
     * @param clipOrKeyframeInterp   片段级或关键帧级插值
     * @return 映射后的进度 [0, 1]
     */
    public static float applyEffectiveCurve(float t,
                                            @Nullable InterpolationType scriptInterpolation,
                                            InterpolationType clipOrKeyframeInterp) {
        InterpolationType effective;
        if (clipOrKeyframeInterp != InterpolationType.LINEAR) {
            effective = clipOrKeyframeInterp;  // 片段/关键帧级覆盖
        } else if (scriptInterpolation != null) {
            effective = scriptInterpolation;   // 使用脚本级默认值
        } else {
            effective = InterpolationType.LINEAR;  // 恒等
        }
        return applyCurve(t, effective);
    }

    /**
     * 数学组合模式插值 — 先应用脚本级曲线，再应用片段/关键帧级曲线
     * <p>
     * 数学组合语义：
     * <pre>
     * adjustedT = applyCurve(applyCurve(t, scriptInterp), clipOrKeyframeInterp)
     * </pre>
     * 产生双重平滑效果，适用于需要更强烈缓入缓出的场景。
     * <p>
     * 注意：当两层都使用 SMOOTH 时，smooth(smooth(t)) 会产生极端的 S 型曲线，
     * 中间段几乎线性而两端极度压缩，可能不符合预期。
     *
     * @param t                      线性进度 [0, 1]
     * @param scriptInterpolation    脚本级插值（全局默认风格），null=LINEAR（跳过第一层）
     * @param clipOrKeyframeInterp   片段级或关键帧级插值
     * @return 映射后的进度 [0, 1]
     */
    public static float applyComposedCurve(float t,
                                           @Nullable InterpolationType scriptInterpolation,
                                           InterpolationType clipOrKeyframeInterp) {
        // 第一层：脚本级曲线
        float afterScript = (scriptInterpolation != null)
                ? applyCurve(t, scriptInterpolation)
                : t;
        // 第二层：片段/关键帧级曲线
        return applyCurve(afterScript, clipOrKeyframeInterp);
    }

    /**
     * 根据曲线组合模式选择对应的曲线映射方法
     *
     * @param t                      线性进度 [0, 1]
     * @param scriptInterpolation    脚本级插值，null=LINEAR
     * @param clipOrKeyframeInterp   片段级或关键帧级插值
     * @param mode                   曲线组合模式，null=OVERRIDE
     * @return 映射后的进度 [0, 1]
     */
    public static float applyCurveWithMode(float t,
                                           @Nullable InterpolationType scriptInterpolation,
                                           InterpolationType clipOrKeyframeInterp,
                                           @Nullable CurveCompositionMode mode) {
        if (mode == CurveCompositionMode.COMPOSED) {
            return applyComposedCurve(t, scriptInterpolation, clipOrKeyframeInterp);
        }
        return applyEffectiveCurve(t, scriptInterpolation, clipOrKeyframeInterp);
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

        // 通过 PathStrategies 注册表获取路径策略（O3: 支持扩展新曲线类型）
        String curveType = (clip.getCurve() != null) ? clip.getCurve().getType() : null;
        PathStrategy strategy = PathStrategies.get(curveType);
        Vec3 result = strategy.interpolate(p0, p3, t, clip.getCurve());

        return MathUtil.sanitizeVec3(result, p0);  // NaN 防护，fallback 到起始帧
    }

    // ========== 朝向插值 ==========

    /**
     * 在两个关键帧之间插值偏航角（角度环绕插值）
     */
    public static float interpolateYaw(CameraKeyframe from, CameraKeyframe to, float t) {
        float result = MathUtil.lerpAngle(from.getYaw(), to.getYaw(), t);
        return MathUtil.sanitizeFloat(result, from.getYaw());  // NaN 防护
    }

    /**
     * 在两个关键帧之间插值俯仰角（线性插值）
     */
    public static float interpolatePitch(CameraKeyframe from, CameraKeyframe to, float t) {
        float result = MathUtil.lerp(from.getPitch(), to.getPitch(), t);
        return MathUtil.sanitizeFloat(result, from.getPitch());  // NaN 防护
    }

    /**
     * 在两个关键帧之间插值滚转角（角度环绕插值）
     */
    public static float interpolateRoll(CameraKeyframe from, CameraKeyframe to, float t) {
        float result = MathUtil.lerpAngle(from.getRoll(), to.getRoll(), t);
        return MathUtil.sanitizeFloat(result, from.getRoll());  // NaN 防护
    }

    // ========== 光学插值 ==========

    public static float interpolateFov(CameraKeyframe from, CameraKeyframe to, float t) {
        float result = MathUtil.lerp(from.getFov(), to.getFov(), t);
        return MathUtil.sanitizeFloat(result, from.getFov());  // NaN 防护
    }

    public static float interpolateZoom(CameraKeyframe from, CameraKeyframe to, float t) {
        float result = MathUtil.lerp(from.getZoom(), to.getZoom(), t);
        return MathUtil.sanitizeFloat(result, from.getZoom());  // NaN 防护
    }

    public static float interpolateDof(CameraKeyframe from, CameraKeyframe to, float t) {
        float result = MathUtil.lerp(from.getDof(), to.getDof(), t);
        return MathUtil.sanitizeFloat(result, from.getDof());  // NaN 防护
    }

    // ========== 时间计算 ==========

    /**
     * 计算片段内的关键帧进度（支持覆盖/组合两种曲线模式）
     * <p>
     * 三层插值体系：
     * <ul>
     *   <li>脚本级（scriptInterpolation）— 全局默认曲线</li>
     *   <li>片段/关键帧级 — 根据 {@link InterpolationScope} 分两条路径：
     *     <ul>
     *       <li>{@link InterpolationScope#CLIP} — 曲线映射应用到整体 clip 进度，
     *           然后在调整后的时间轴上找关键帧段并线性插值。</li>
     *       <li>{@link InterpolationScope#SEGMENT} — 先找关键帧段，计算段内线性进度，
     *           再对段内进度应用曲线映射。支持逐关键帧插值覆盖。</li>
     *     </ul>
     *   </li>
     *   <li>曲线组合模式（curveCompositionMode）：
     *     <ul>
     *       <li>{@link CurveCompositionMode#OVERRIDE} — 片段/关键帧级覆盖脚本级默认值</li>
     *       <li>{@link CurveCompositionMode#COMPOSED} — 先脚本级，再片段/关键帧级（双重平滑）</li>
     *     </ul>
     *   </li>
     * </ul>
     * <p>
     * 循环处理：如果 clip.loop=true，对片段内时间取模实现循环。
     *
     * @param clipTime            片段内时间（秒）
     * @param clip                所属片段
     * @param scriptInterpolation 脚本级插值（全局默认风格），null=LINEAR（不施加全局曲线）
     * @param compositionMode     曲线组合模式，null=OVERRIDE
     * @return 插值结果，包含 from/to 关键帧和 adjustedT；
     *         如果时间在关键帧范围外，返回 null
     */
    public static InterpolationResult computeInterpolation(float clipTime, CameraClip clip,
                                                           @Nullable InterpolationType scriptInterpolation,
                                                           @Nullable CurveCompositionMode compositionMode) {
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
            return computeClipScope(effectiveTime, clip, keyframes, scriptInterpolation, compositionMode);
        } else {
            return computeSegmentScope(effectiveTime, clip, keyframes, scriptInterpolation, compositionMode);
        }
    }

    /**
     * 兼容旧签名 — 默认使用 OVERRIDE 模式
     */
    public static InterpolationResult computeInterpolation(float clipTime, CameraClip clip,
                                                           @Nullable InterpolationType scriptInterpolation) {
        return computeInterpolation(clipTime, clip, scriptInterpolation, null);
    }

    /**
     * CLIP scope — 曲线映射应用到整体 clip 进度
     * <p>
     * 算法：
     * 1. 计算整体进度 overallT = (effectiveTime - firstTime) / (lastTime - firstTime)
     * 2. 根据组合模式应用曲线映射到整体进度
     * 3. 映射回时间轴 adjustedTime = firstTime + adjustedOverallT * overallDuration
     * 4. 在 adjustedTime 上找关键帧段 + 线性插值（不再 applyCurve）
     */
    private static InterpolationResult computeClipScope(float effectiveTime, CameraClip clip,
                                                        List<CameraKeyframe> keyframes,
                                                        @Nullable InterpolationType scriptInterpolation,
                                                        @Nullable CurveCompositionMode compositionMode) {
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

        // 2. 根据组合模式应用曲线映射到整体进度
        float adjustedOverallT = applyCurveWithMode(overallT, scriptInterpolation, clip.getInterpolation(), compositionMode);

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
     * SEGMENT scope — 曲线映射应用到每个关键帧段
     * <p>
     * 算法：
     * 1. 找到当前所处的关键帧段
     * 2. 计算段内线性进度 t
     * 3. 确定该段的插值类型（优先使用 to 关键帧的覆盖，否则用 clip 默认）
     * 4. 根据组合模式对段内进度应用曲线映射
     */
    private static InterpolationResult computeSegmentScope(float effectiveTime, CameraClip clip,
                                                           List<CameraKeyframe> keyframes,
                                                           @Nullable InterpolationType scriptInterpolation,
                                                           @Nullable CurveCompositionMode compositionMode) {
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

        // 根据组合模式应用插值曲线映射
        float adjustedT = applyCurveWithMode(t, scriptInterpolation, segmentInterpolation, compositionMode);

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
