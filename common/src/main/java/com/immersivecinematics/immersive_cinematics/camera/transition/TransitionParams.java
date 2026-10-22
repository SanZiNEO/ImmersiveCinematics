package com.immersivecinematics.immersive_cinematics.camera.transition;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.math.MathModel;

/**
 * 过渡分参数声明（脚本字段 {@code transition_params} 的解析形态）——未声明的通道 / 键保留「未声明」标记，
 * 由 {@link TransitionSpec#resolve} 按片段级缺省补齐。
 *
 * 数组下标 = 通道位序（{@code position} … {@code zoom}；唯一定义点 = {@link CameraChannels}）。
 * 未声明标记：{@code mode = null}、{@code duration = NaN}、{@code curve = null}。
 * 分配契约：解析期由 {@code ScriptParser} 建成（形态非法即拒绝加载），之后只读。
 */
public final class TransitionParams {

    /** 通道数（= 六参数通道数）。 */
    public static final int CHANNELS = CameraChannels.NAMES.size();

    /** 每通道模式；{@code null} = 未声明。 */
    private final TransitionMode[] modes;

    /** 每通道时长（秒）；{@code NaN} = 未声明。 */
    private final float[] durations;

    /** 每通道权重曲线定义；{@code null} = 未声明。 */
    private final MathModel[] curves;

    /** 三个数组长度均须为 {@link #CHANNELS}；入参被克隆，构造后外部改动不影响本实例。 */
    public TransitionParams(TransitionMode[] modes, float[] durations, MathModel[] curves) {
        this.modes = modes.clone();
        this.durations = durations.clone();
        this.curves = curves.clone();
    }

    /** 该通道声明的模式；{@code null} = 未声明（继承缺省 {@link TransitionMode#BLEND}）。 */
    public TransitionMode mode(int channel) {
        return modes[channel];
    }

    /** 该通道声明的时长（秒）；{@code NaN} = 未声明（继承片段 {@code transition_duration}）。 */
    public float duration(int channel) {
        return durations[channel];
    }

    /** 该通道声明的权重曲线；{@code null} = 未声明（继承片段 {@code transition_curve}）。 */
    public MathModel curve(int channel) {
        return curves[channel];
    }
}
