package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;

import java.util.List;

/**
 * 调色采样器 — 在片段本地时间处把一段 clip 的调色字段插值成一份 {@link ColorAdjustParams}。
 *
 * 纯关键帧求值、无状态：47 个标量通道 + 10 条曲线强度 + LUT 强度在本地时间处插值，
 * 曲线 LUT 与 .cube LUT 随 clip 缓存复用。两个使用方：ADJUST 轨（master，作用于整体画面）与
 * {@link ScriptPlayer#collectCameraLanes()} 的逐 lane 采样（每条 lane 用自己的相机片段与
 * {@link CameraLane#clipLocalTime()}，渲染侧在该 lane 渲染完成、合成之前开 pass）。
 * 结果只承载 RGB：参数语义 / 范围 / 缺省见 {@link ColorAdjustParams}，像素级契约见 {@code ic_color_adjust.fsh}。
 * LUT 与层级混合只属 ADJUST 轨（master）：lane 路径恒得 {@code lut = null} 与缺省混合。
 */
public final class ColorAdjustSampler {

    private ColorAdjustSampler() {
    }

    /**
     * 本片段在片段本地时间处的全部取值（顺序 = {@link ColorAdjustParams} 分量顺序 = shader 操作栈顺序）。
     *
     * 标量通道缺省 0 = 无效果；曲线 LUT 按 clip 缓存，此处只插值各曲线的强度（缺省 1，无曲线 → 0）。
     */
    public static ColorAdjustParams sample(Clip clip, float localTime) {
        List<Keyframe> keyframes = clip.getKeyframes();
        float[] curveLut = lut(clip.getColorCurve());
        float[] rCurveLut = lut(clip.getRCurve());
        float[] gCurveLut = lut(clip.getGCurve());
        float[] bCurveLut = lut(clip.getBCurve());
        float[] hvHLut = lut(clip.getHvHCurve());
        float[] hvSLut = lut(clip.getHvSCurve());
        float[] hvLLut = lut(clip.getHvLCurve());
        float[] lvSLut = lut(clip.getLvSCurve());
        float[] svSLut = lut(clip.getSvSCurve());
        float[] svLLut = lut(clip.getSvLCurve());
        // LUT：解析失败 = null = 无 LUT（此时强度置 0）；解析结果按路径缓存、按 clip 记引用。
        CubeLut lut = CubeLutLoader.forClip(clip.getClipId(), clip.getLut());
        return new ColorAdjustParams(
                channel(keyframes, localTime, "exposure"),
                channel(keyframes, localTime, "contrast"),
                channel(keyframes, localTime, "highlights"),
                channel(keyframes, localTime, "shadows"),
                channel(keyframes, localTime, "whites"),
                channel(keyframes, localTime, "blacks"),
                channel(keyframes, localTime, "hue"),
                channel(keyframes, localTime, "saturation"),
                channel(keyframes, localTime, "vibrance"),
                channel(keyframes, localTime, "lightness"),
                channel(keyframes, localTime, "temperature"),
                channel(keyframes, localTime, "tint"),
                channel(keyframes, localTime, "red"),
                channel(keyframes, localTime, "green"),
                channel(keyframes, localTime, "blue"),
                channel(keyframes, localTime, "mix_rr"),
                channel(keyframes, localTime, "mix_rg"),
                channel(keyframes, localTime, "mix_rb"),
                channel(keyframes, localTime, "mix_gr"),
                channel(keyframes, localTime, "mix_gg"),
                channel(keyframes, localTime, "mix_gb"),
                channel(keyframes, localTime, "mix_br"),
                channel(keyframes, localTime, "mix_bg"),
                channel(keyframes, localTime, "mix_bb"),
                curveLut, rCurveLut, gCurveLut, bCurveLut,
                strength(keyframes, localTime, "curve_strength", curveLut),
                strength(keyframes, localTime, "r_curve_strength", rCurveLut),
                strength(keyframes, localTime, "g_curve_strength", gCurveLut),
                strength(keyframes, localTime, "b_curve_strength", bCurveLut),
                channel(keyframes, localTime, "lift_r"),
                channel(keyframes, localTime, "lift_g"),
                channel(keyframes, localTime, "lift_b"),
                channel(keyframes, localTime, "gamma_r"),
                channel(keyframes, localTime, "gamma_g"),
                channel(keyframes, localTime, "gamma_b"),
                channel(keyframes, localTime, "gain_r"),
                channel(keyframes, localTime, "gain_g"),
                channel(keyframes, localTime, "gain_b"),
                // LUT：无 LUT → 0（整步跳过）；有 LUT → lut_strength 插值（缺省 1 = 全量生效）
                lut,
                lut == null ? 0.0F
                        : KeyframeInterpolator.interpolateChannel(keyframes, localTime, "lut_strength", 1.0F),
                // LUT 输入域适配：clip 级幂指数（缺省 1 = 不变换）；无 LUT 时被忽略（整步不生效）
                clip.getLutInputGamma(),
                hvHLut, hvSLut, hvLLut, lvSLut, svSLut, svLLut,
                strength(keyframes, localTime, "hv_h_strength", hvHLut),
                strength(keyframes, localTime, "hv_s_strength", hvSLut),
                strength(keyframes, localTime, "hv_l_strength", hvLLut),
                strength(keyframes, localTime, "lv_s_strength", lvSLut),
                strength(keyframes, localTime, "sv_s_strength", svSLut),
                strength(keyframes, localTime, "sv_l_strength", svLLut),
                channel(keyframes, localTime, "hue_red"),
                channel(keyframes, localTime, "sat_red"),
                channel(keyframes, localTime, "hue_yellow"),
                channel(keyframes, localTime, "sat_yellow"),
                channel(keyframes, localTime, "hue_green"),
                channel(keyframes, localTime, "sat_green"),
                channel(keyframes, localTime, "hue_cyan"),
                channel(keyframes, localTime, "sat_cyan"),
                channel(keyframes, localTime, "hue_blue"),
                channel(keyframes, localTime, "sat_blue"),
                channel(keyframes, localTime, "hue_magenta"),
                channel(keyframes, localTime, "sat_magenta"),
                channel(keyframes, localTime, "grayscale"),
                channel(keyframes, localTime, "invert"),
                // 层级混合：clip 级 blend_mode 编码 + 关键帧 blend_amount（缺省 1 = 全量生效）；
                // 只有 ADJUST 轨（master）带混合字段，lane 路径恒得缺省（normal + 1 = 恒等）。
                ColorAdjustParams.blendModeCode(clip.getBlendMode()),
                clip.getTrackType() == TrackType.ADJUST
                        ? KeyframeInterpolator.interpolateChannel(keyframes, localTime, "blend_amount", 1.0F)
                        : 1.0F);
    }

    /** clip 级曲线 → 它的 256 点 LUT（{@code null} = 本片段没有那条曲线）；LUT 随曲线对象按 clip 缓存。 */
    private static float[] lut(ColorCurve curve) {
        return curve != null ? curve.lut() : null;
    }

    /** 曲线强度：没有对应曲线 → 0（该步不生效）；有曲线 → 关键帧插值，缺省 1 = 全量生效。 */
    private static float strength(List<Keyframe> keyframes, float localTime, String key, float[] lut) {
        return lut == null ? 0.0F : KeyframeInterpolator.interpolateChannel(keyframes, localTime, key, 1.0F);
    }

    /** 单通道取值：缺省 0 = 无效果（关键帧没写该字段就是不做这项调整）。 */
    private static float channel(List<Keyframe> keyframes, float localTime, String key) {
        return KeyframeInterpolator.interpolateChannel(keyframes, localTime, key, 0.0F);
    }
}
