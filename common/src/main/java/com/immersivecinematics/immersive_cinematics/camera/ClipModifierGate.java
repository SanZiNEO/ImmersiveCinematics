package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraStateBuffer;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ComposeMode;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipTimeSource;

import java.util.function.DoubleSupplier;

/**
 * 片段级静态 Modifier 的注册形态：活跃窗门 + 转发到声明（{@link ClipModifier}）。
 *
 * 活跃判定：声明只在它所在片段的活跃窗内参与合成——窗由执行账本给出
 * （{@link ClipTimeSource#isClipActive}，有限 = {@code [windowStart, windowEnd)}，无限自起点起永续）；
 * 本片段是 morph 转场的前一段时窗末再延长半个过渡窗口（{@link TransitionSpec#windowEnd}，有限窗口末端取
 * {@link ClipTimeSource#finiteWindowEnd}；重叠窗内前后两侧的声明并存，叠加序 = 优先级升序 → 注册序）。
 * 时间取所属播放实例本帧的时间轴读数（与轨道分发同一口径，见 {@code ScriptPlayer.timelineTime()}）。
 * 分配契约：{@link #isActiveThisFrame()} 每帧只读一个 float + 两次比较，零分配；本对象在播放实例
 * 注册 / 脚本替换时各建一次，不在帧内创建。
 * 线程契约：仅客户端主线程。
 */
final class ClipModifierGate implements CameraModifier {

    /** 声明（掩码 / 合成模式 / 应用序 / 值）。 */
    private final ClipModifier declaration;

    /** 声明所在片段（活跃窗的唯一依据）。 */
    private final Clip clip;

    /** 同轨下一片段（本片段是 morph 前一段时窗末延长半个过渡时长）；无下一片段 = {@code null}。 */
    private final Clip next;

    /** 执行账本时间来源（活跃判定 / 有限窗口末端）。 */
    private final ClipTimeSource timeSource;

    /** 本实例本帧的时间轴读数来源（注册时注入；每帧只读一次）。 */
    private final DoubleSupplier timelineTime;

    /** 活跃窗起点 = 片段起始时间（秒）。 */
    private final double windowStart;

    /**
     * @param declaration 片段级静态声明
     * @param clip        声明所在片段（活跃窗的唯一依据）
     * @param next        同轨下一片段（本片段是 morph 前一段时窗末延长半个过渡时长）；无下一片段传 {@code null}
     * @param timeSource  执行账本时间来源
     * @param timelineTime 所属实例本帧的时间轴读数来源
     */
    ClipModifierGate(ClipModifier declaration, Clip clip, Clip next, ClipTimeSource timeSource,
                     DoubleSupplier timelineTime) {
        this.declaration = declaration;
        this.clip = clip;
        this.next = next;
        this.timeSource = timeSource;
        this.timelineTime = timelineTime;
        this.windowStart = clip.getStartTime();
    }

    @Override
    public int modifierPriority() {
        return declaration.modifierPriority();
    }

    @Override
    public int channelMask() {
        return declaration.channelMask();
    }

    @Override
    public ComposeMode composeMode() {
        return declaration.composeMode();
    }

    @Override
    public boolean isActiveThisFrame() {
        double time = timelineTime.getAsDouble();
        if (timeSource.isClipActive(clip, time)) return true;
        // morph 前一段：账本窗口外仍可延长半个过渡窗口（无限片段没有有限末端，不延长）
        if (next == null || clip.isEffectivelyInfinite() || !clip.isMorph()) return false;
        TransitionSpec spec = clip.getTransitionSpec();
        return spec != null && time >= windowStart
                && time < spec.windowEnd(timeSource.finiteWindowEnd(clip));
    }

    @Override
    public void writeValue(CameraStateBuffer out) {
        declaration.writeValue(out);
    }
}
