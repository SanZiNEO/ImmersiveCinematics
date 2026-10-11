package com.immersivecinematics.immersive_cinematics.camera.transition;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.math.MathModel;
import com.immersivecinematics.immersive_cinematics.math.MathModelInstance;

/**
 * 过渡声明（已归一的最终形态）——六通道各自的模式 / 时长 / 权重曲线 + 打断规则。
 *
 * 缺省（不声明 {@code transition_params} / {@code transition_curve} / {@code transition_interrupt}）= 现状：
 * 全六通道 {@link TransitionMode#BLEND}、同一时长（片段 {@code transition_duration}）、无曲线
 * （恒等 = 线性斜坡）、{@link TransitionInterrupt#BLEND}。
 * 窗口口径：每通道窗口 = 以片段交界时刻为中心、该通道时长为宽度；{@link #maxDuration} = 非 CUT 通道的
 * 最大时长（CUT 通道不做过渡、不占窗口；全部为 CUT 或时长全为 0 = 无过渡）。
 * 数组下标 = 通道位序（{@code position=0} … {@code zoom=5}，见 {@link CameraChannels}）；时长单位 = 秒。
 * 分配契约：解析期建成后只读；{@link #instantiateCurves} 在过渡开始时调用一次（不在逐帧路径）。
 */
public final class TransitionSpec {

    /** 通道数（= 六参数通道数；数组下标 = 通道位序）。 */
    public static final int CHANNELS = CameraChannels.NAMES.size();

    /** 每通道模式（非 {@code null}）。 */
    private final TransitionMode[] modes;

    /** 每通道时长（秒；{@code >= 0}，0 = 该通道不做过渡）。 */
    private final float[] durations;

    /** 每通道权重曲线定义；{@code null} 元素 = 恒等（线性斜坡）。 */
    private final MathModel[] curves;

    /** 打断规则。 */
    private final TransitionInterrupt interrupt;

    /** 过渡窗口半宽来源：非 CUT 通道的最大时长（秒）；0 = 无过渡。 */
    private final float maxDuration;

    private TransitionSpec(TransitionMode[] modes, float[] durations, MathModel[] curves,
                           TransitionInterrupt interrupt) {
        this.modes = modes;
        this.durations = durations;
        this.curves = curves;
        this.interrupt = interrupt;
        float max = 0f;
        for (int i = 0; i < CHANNELS; i++) {
            if (modes[i] != TransitionMode.CUT && durations[i] > max) {
                max = durations[i];
            }
        }
        this.maxDuration = max;
    }

    /**
     * 分参数声明 → 最终形态：未声明通道 / 键按片段级缺省补齐（不声明任何新字段 = 现状：
     * 全六通道 {@link TransitionMode#BLEND}、同一时长、无曲线、{@link TransitionInterrupt#BLEND}）。
     *
     * @param params          分参数声明；{@code null} = 未声明（全缺省）
     * @param defaultDuration 片段级缺省时长（秒）；非有限 / ≤ 0 = 该通道无过渡
     * @param defaultCurve    片段级缺省曲线；{@code null} = 恒等（线性斜坡）
     * @param interrupt       打断规则；{@code null} = {@link TransitionInterrupt#BLEND}
     * @return 已归一的声明
     */
    public static TransitionSpec resolve(TransitionParams params, float defaultDuration,
                                         MathModel defaultCurve, TransitionInterrupt interrupt) {
        return build(params, defaultDuration, defaultCurve, interrupt);
    }

    /** 该通道模式。 */
    public TransitionMode mode(int channel) {
        return modes[channel];
    }

    /** 该通道时长（秒；0 = 该通道不做过渡）。 */
    public float duration(int channel) {
        return durations[channel];
    }

    /** 该通道权重曲线；{@code null} = 恒等（线性斜坡）。 */
    public MathModel curve(int channel) {
        return curves[channel];
    }

    /** 打断规则。 */
    public TransitionInterrupt interrupt() {
        return interrupt;
    }

    /** 过渡窗口半宽来源：非 CUT 通道的最大时长（秒）；0 = 无过渡。 */
    public float maxDuration() {
        return maxDuration;
    }

    /** 过渡窗口起点（秒）= 交界 − 最大时长/2。 */
    public float windowStart(float boundary) {
        return boundary - maxDuration / 2f;
    }

    /** 过渡窗口末端（秒，不含）= 交界 + 最大时长/2。 */
    public float windowEnd(float boundary) {
        return boundary + maxDuration / 2f;
    }

    /**
     * 本帧时刻是否落在过渡窗口内（窗口口径的唯一判据：三个消费方——轨道驱动 / 活跃判定 /
     * Modifier 活跃窗延长——都用它，避免各算一份）。
     *
     * @param now      当前时刻（秒）
     * @param boundary 交界时刻（秒）
     * @return {@code true} = 在窗口内；最大时长 ≤ 0（无过渡）时恒 {@code false}
     */
    public boolean covers(float now, float boundary) {
        return maxDuration > 0f && now >= windowStart(boundary) && now < windowEnd(boundary);
    }

    /**
     * 按声明实例化权重曲线（过渡开始时调用一次）。
     *
     * @return 长度 {@link #CHANNELS} 的实例数组；{@code null} 元素 = 该通道无曲线（恒等）
     */
    public MathModelInstance[] instantiateCurves() {
        MathModelInstance[] instances = new MathModelInstance[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            if (curves[i] != null) {
                instances[i] = curves[i].instantiate();
            }
        }
        return instances;
    }

    /** 归一：未声明通道 / 键取片段级缺省；时长非有限 / ≤ 0 取片段级缺省（片段级也 ≤ 0 = 无过渡）。 */
    private static TransitionSpec build(TransitionParams params, float defaultDuration,
                                        MathModel defaultCurve, TransitionInterrupt interrupt) {
        float fallback = Float.isFinite(defaultDuration) && defaultDuration > 0f ? defaultDuration : 0f;
        TransitionMode[] modes = new TransitionMode[CHANNELS];
        float[] durations = new float[CHANNELS];
        MathModel[] curves = new MathModel[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            TransitionMode mode = params != null ? params.mode(i) : null;
            float declared = params != null ? params.duration(i) : Float.NaN;
            MathModel curve = params != null ? params.curve(i) : null;
            modes[i] = mode != null ? mode : TransitionMode.BLEND;
            durations[i] = Float.isFinite(declared) && declared > 0f ? declared : fallback;
            curves[i] = curve != null ? curve : defaultCurve;
        }
        return new TransitionSpec(modes, durations, curves,
                interrupt != null ? interrupt : TransitionInterrupt.BLEND);
    }
}
