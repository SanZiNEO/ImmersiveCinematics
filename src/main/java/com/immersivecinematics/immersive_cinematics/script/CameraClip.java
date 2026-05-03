package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 相机片段 — 时间轴上的一段镜头动画
 * <p>
 * 速度驱动模型下，片段控制速度曲线和路径：
 * <ul>
 *   <li>{@code speed} — 片段整体速度倍率 (0~2)，默认 1.0</li>
 *   <li>{@code interpolation} — 速度曲线类型 (linear/smooth)</li>
 *   <li>{@code property_overrides} — 可选，属性级速度覆盖</li>
 *   <li>{@code curve} — 贝塞尔路径控制（与速度正交，仅影响位置）</li>
 * </ul>
 * <p>
 * 速度驱动公式链：
 * <pre>
 * t ──[速度曲线]──→ v(t) ──[积分]──→ s(t) ──[弧长贝塞尔或lerp]──→ 属性值
 * </pre>
 * <p>
 * 无限时长与循环语义：
 * <ul>
 *   <li>duration > 0, loop=false: 播放一次，到 duration 结束</li>
 *   <li>duration > 0, loop=true, loopCount=-1: 无限循环直到 duration 耗尽</li>
 *   <li>duration > 0, loop=true, loopCount=3: 循环3次后停在末帧</li>
 *   <li>duration=-1, loop=false: 静态镜头或播放一次后停在末帧，永不自动结束</li>
 *   <li>duration=-1, loop=true, loopCount=-1: 无限循环（巡逻监控）</li>
 * </ul>
 */
public class CameraClip {

    /** 在总时间轴上的开始时间（秒） */
    private final float startTime;

    /** 片段持续时长（秒），负数 = 无限时长 */
    private final float duration;

    /** 与上一个片段的过渡方式 */
    private final TransitionType transition;

    /** morph 过渡时长（秒），仅 transition=morph 时有效 */
    private final float transitionDuration;

    /** 片段整体速度倍率 (0~2)，默认 1.0 */
    private final float speed;

    /** 速度曲线类型 (linear/smooth) */
    private final InterpolationType interpolation;

    /** 贝塞尔曲线路径控制（仅影响位置路径），null=直线插值 */
    private final BezierCurve curve;

    /** 坐标模式：true=relative(dx/dy/dz)，false=absolute(x/y/z) */
    private final boolean positionModeRelative;

    /** 片段内关键帧动画是否循环 */
    private final boolean loop;

    /** 循环次数限制，-1=无限循环；仅 loop=true 时有效 */
    private final int loopCount;

    /** 关键帧数组 */
    private final List<CameraKeyframe> keyframes;

    /** 属性级速度覆盖（可选），key=属性名，value=覆盖配置 */
    private final Map<String, PropertyOverride> propertyOverrides;

    public CameraClip(float startTime, float duration, TransitionType transition, float transitionDuration,
                      float speed, InterpolationType interpolation,
                      BezierCurve curve, boolean positionModeRelative,
                      boolean loop, int loopCount, List<CameraKeyframe> keyframes,
                      Map<String, PropertyOverride> propertyOverrides) {
        this.startTime = startTime;
        this.duration = duration;
        this.transition = transition;
        this.transitionDuration = transitionDuration;
        this.speed = speed;
        this.interpolation = interpolation;
        this.curve = curve;
        this.positionModeRelative = positionModeRelative;
        this.loop = loop;
        this.loopCount = loopCount;
        this.keyframes = keyframes != null ? keyframes : Collections.emptyList();
        this.propertyOverrides = propertyOverrides != null ? propertyOverrides : Collections.emptyMap();
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public TransitionType getTransition() { return transition; }
    public float getTransitionDuration() { return transitionDuration; }
    public float getSpeed() { return speed; }
    public InterpolationType getInterpolation() { return interpolation; }
    public BezierCurve getCurve() { return curve; }
    public boolean isPositionModeRelative() { return positionModeRelative; }
    public boolean isLoop() { return loop; }
    public int getLoopCount() { return loopCount; }
    public List<CameraKeyframe> getKeyframes() { return keyframes; }
    public Map<String, PropertyOverride> getPropertyOverrides() { return propertyOverrides; }

    /** 是否为无限时长片段（负数即视为无限时长） */
    public boolean isInfinite() { return duration < 0f; }

    /** 是否为 morph 过渡 */
    public boolean isMorph() { return transition == TransitionType.MORPH; }

    /** 获取指定属性的速度覆盖，无覆盖时返回 null */
    public PropertyOverride getPropertyOverride(String propertyName) {
        return propertyOverrides.get(propertyName);
    }

    @Override
    public String toString() {
        return String.format("CameraClip{start=%.2f, dur=%.2f, speed=%.2f, interp=%s, posMode=%s, loop=%s, keyframes=%d, overrides=%d}",
                startTime, duration, speed, interpolation,
                positionModeRelative ? "relative" : "absolute",
                loop, keyframes != null ? keyframes.size() : 0,
                propertyOverrides.size());
    }
}
