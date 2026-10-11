package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraStateBuffer;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ComposeMode;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.function.DoubleSupplier;

/**
 * 片段级静态 Modifier 的注册形态：活跃窗门 + 转发到声明（{@link ClipModifier}）。
 *
 * 活跃判定：声明只在它所在片段的活跃窗内参与合成——窗 = {@code [startTime, windowEnd)}，永不结束的片段
 * （{@link Clip#isEffectivelyInfinite()}）自 {@code startTime} 起永续；本片段是 morph 转场的前一段时
 * 窗末再延长半个过渡窗口（{@link TransitionSpec#windowEnd}，与轨道驱动 / 活跃判定同一口径；
 * 重叠窗内前后两侧的声明并存，叠加序照旧：优先级升序 → 注册序）。
 * 时间取所属播放实例本帧的时间轴读数（与轨道分发同一口径，见 {@code ScriptPlayer.timelineTime()}）。
 * 分配契约：{@link #isActiveThisFrame()} 每帧只读一个 float + 两次比较，零分配；本对象在播放实例
 * 注册 / 脚本替换时各建一次，不在帧内创建。
 * 线程契约：仅客户端主线程。
 */
final class ClipModifierGate implements CameraModifier {

    /** 声明（掩码 / 合成模式 / 应用序 / 值）。 */
    private final ClipModifier declaration;

    /** 本实例本帧的时间轴读数来源（注册时注入；每帧只读一次）。 */
    private final DoubleSupplier timelineTime;

    /** 活跃窗起点 = 片段起始时间（秒）。 */
    private final float windowStart;

    /** 活跃窗末端（不含）；永不结束的片段 = {@code Float.MAX_VALUE}。 */
    private final float windowEnd;

    /**
     * @param declaration 片段级静态声明
     * @param clip        声明所在片段（活跃窗的唯一依据）
     * @param next        同轨下一片段（本片段是 morph 前一段时窗末延长半个过渡时长）；无下一片段传 {@code null}
     * @param timelineTime 所属实例本帧的时间轴读数来源
     */
    ClipModifierGate(ClipModifier declaration, Clip clip, Clip next, DoubleSupplier timelineTime) {
        this.declaration = declaration;
        this.timelineTime = timelineTime;
        this.windowStart = clip.getStartTime();
        this.windowEnd = windowEnd(clip, next);
    }

    /** 活跃窗末端：普通片段 = {@code start + duration}（循环 = 周期 × 次数）；morph 前一段 + 半个过渡窗口。 */
    private static float windowEnd(Clip clip, Clip next) {
        float end = clip.getWindowEnd();
        if (next == null || clip.isEffectivelyInfinite() || !clip.isMorph()) return end;
        TransitionSpec spec = clip.getTransitionSpec();
        return spec == null ? end : spec.windowEnd(end);
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
        float time = (float) timelineTime.getAsDouble();
        return time >= windowStart && time < windowEnd;
    }

    @Override
    public void writeValue(CameraStateBuffer out) {
        declaration.writeValue(out);
    }
}
