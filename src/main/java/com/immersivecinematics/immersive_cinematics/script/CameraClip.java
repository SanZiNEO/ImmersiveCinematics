package com.immersivecinematics.immersive_cinematics.script;

import java.util.Collections;
import java.util.List;

/**
 * 相机片段 — 时间轴上的一段镜头动画
 * <p>
 * 包含片段属性（transition/interpolation/interpolationScope/curve/position_mode/loop 等）
 * 和关键帧数组（time/position/yaw/pitch/roll/fov/zoom/dof）。
 * <p>
 * 过渡方式：
 * <ul>
 *   <li>"cut" — 硬切换，对应 staged → commitStagedState() 原子替换</li>
 *   <li>"crossfade" — 交叉淡化，在两个片段的重叠区间内按比例混合所有属性</li>
 * </ul>
 * <p>
 * 插值作用域（interpolationScope）：
 * <ul>
 *   <li>{@link InterpolationScope#CLIP} — 曲线映射应用到整体 clip 进度，关键帧段内线性。
 *       整个 clip 只在开头/结尾有缓入缓出，中间关键帧处速度连续。</li>
 *   <li>{@link InterpolationScope#SEGMENT} — 曲线映射应用到每个关键帧段（传统行为）。
 *       每段独立缓入缓出，中间关键帧处速度归零后重新加速。</li>
 * </ul>
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

    /** 交叉淡化时长（秒），仅 transition=crossfade 时有效 */
    private final float crossfadeDuration;

    /** 插值曲线类型 */
    private final InterpolationType interpolation;

    /** 插值作用域：CLIP=整体进度映射，SEGMENT=逐段映射 */
    private final InterpolationScope interpolationScope;

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

    public CameraClip(float startTime, float duration, TransitionType transition, float crossfadeDuration,
                      InterpolationType interpolation, InterpolationScope interpolationScope,
                      BezierCurve curve, boolean positionModeRelative,
                      boolean loop, int loopCount, List<CameraKeyframe> keyframes) {
        this.startTime = startTime;
        this.duration = duration;
        this.transition = transition;
        this.crossfadeDuration = crossfadeDuration;
        this.interpolation = interpolation;
        this.interpolationScope = interpolationScope;
        this.curve = curve;
        this.positionModeRelative = positionModeRelative;
        this.loop = loop;
        this.loopCount = loopCount;
        this.keyframes = keyframes != null ? keyframes : Collections.emptyList();
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public TransitionType getTransition() { return transition; }
    public float getCrossfadeDuration() { return crossfadeDuration; }
    public InterpolationType getInterpolation() { return interpolation; }
    public InterpolationScope getInterpolationScope() { return interpolationScope; }
    public BezierCurve getCurve() { return curve; }
    public boolean isPositionModeRelative() { return positionModeRelative; }
    public boolean isLoop() { return loop; }
    public int getLoopCount() { return loopCount; }
    public List<CameraKeyframe> getKeyframes() { return keyframes; }

    /** 是否为无限时长片段（负数即视为无限时长） */
    public boolean isInfinite() { return duration < 0f; }

    /** 是否为交叉淡化过渡 */
    public boolean isCrossfade() { return transition == TransitionType.CROSSFADE; }

    @Override
    public String toString() {
        return String.format("CameraClip{start=%.2f, dur=%.2f, interp=%s, scope=%s, posMode=%s, loop=%s, keyframes=%d}",
                startTime, duration, interpolation, interpolationScope,
                positionModeRelative ? "relative" : "absolute",
                loop, keyframes != null ? keyframes.size() : 0);
    }
}
