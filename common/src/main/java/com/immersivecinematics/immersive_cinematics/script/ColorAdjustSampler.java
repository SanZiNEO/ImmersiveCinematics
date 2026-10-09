package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;

import java.util.List;

/**
 * 调色采样器 —— 在<b>片段本地时间</b>处把一段 clip 的调色字段插值成一份 {@link ColorAdjustParams}。
 *
 * <h2>职责</h2>
 * 纯粹的关键帧求值：47 个标量通道 + 十条曲线强度 + LUT 强度在本地时间处插值，曲线 LUT 与 .cube LUT 随 clip 缓存复用。
 * 无状态、无副作用——ADJUST 轨（master）与每条相机片段（lane 级）都从这里取同一份口径的采样结果。
 *
 * <h2>两个使用方</h2>
 * <ul>
 *   <li><b>master</b>：{@link AdjustTrackPlayer} 在 ADJUST 轨活跃 clip 的本地时间处采样，
 *       结果发布给 {@code MasterColorAdjust}，作用于<b>整体画面</b>。</li>
 *   <li><b>lane 级</b>：{@link ScriptPlayer#collectCameraLanes()} 对每条 lane 用<b>它自己的相机片段</b>
 *       与 {@link CameraLane#clipLocalTime()} 采样（重叠窗口下多 lane 各自独立采样），
 *       渲染侧在该 lane 渲染完成、合成之前开 pass。</li>
 * </ul>
 *
 * <h2>只承载 RGB</h2>
 * 采样结果（{@link ColorAdjustParams}）只描述 <b>RGB</b> 调整——57 个通道 + 10 个强度 + 10 个 LUT 里
 * <b>没有任何 alpha / opacity 参数</b>。调色 pass 只动 RGB、alpha 逐位直通；
 * 透明度一律在<b>合成之后</b>由合成层（{@code opacity} / {@code ColorModulator.a}）调控，
 * 既不烤进画面、也不在调色 pass 里先处理。
 *
 * <h2>数据口径</h2>
 * 47 个标量通道全部是<b>关键帧字段</b>（缺省全 0 = 无效果）；曲线组是 clip 级字段（不随时间变），
 * 各自的 256 点 LUT 由 {@link ColorCurve} 对象持有（逐帧拿到同一个数组），此处只把各自的混合强度
 * 在本地时间处插值——<b>强度缺省 1</b>：写了曲线就是全量生效，关键帧把强度写回 0 即曲线淡出；
 * 无曲线时该强度置 0（那一步不生效）。插值走 {@link KeyframeInterpolator#interpolateChannel}（匀速线性），
 * 与其它轨道的标量通道同一口径。<b>LUT</b>（clip 级 {@code lut} 文件名 / {@code lut_input_gamma} + 关键帧 {@code lut_strength}）
 * 同款：文件名在这里换成缓存实例（{@link CubeLutLoader#forClip}，失败 = {@code null} = 无 LUT），
 * 强度缺省 1、无 LUT 时置 0（那一步不生效）；{@code lut_input_gamma}（缺省 1 = 不变换，必须 &gt; 0）
 * 是 clip 级查表输入域适配的幂指数，同样不随时间变、无 LUT 时被忽略。
 * LUT 是<b>整体画面（master）</b>处理——
 * {@link Clip#getLut()} 只在 ADJUST 轨给值，故 lane 路径采样到的 {@code lut} 恒为 {@code null}。
 *
 * <h2>层级混合（混合模式作用于调整层）</h2>
 * clip 级 {@code blend_mode}（枚举名 → 编码 0 ~ 4，见 {@link ColorAdjustParams#blendModeCode(String)}）
 * + 关键帧 {@code blend_amount}（0 ~ 1，<b>缺省 1</b> = 全量生效）同样只属 <b>ADJUST 轨（master）</b>：
 * {@link Clip#getBlendMode()} 只在 ADJUST 轨给值、{@code blend_amount} 只在该轨插值，
 * lane 路径恒得缺省（{@code normal} + {@code 1} = 混合步骤恒等）。两者一起描述
 * 「调整层输出 vs 基画面」的层级混合（在全部操作栈步骤之后整体施加，见 {@link ColorAdjustParams}）。
 */
public final class ColorAdjustSampler {

    private ColorAdjustSampler() {
    }

    /**
     * 本片段在<b>片段本地时间</b>处的全部取值（顺序 = {@link ColorAdjustParams} 分量顺序 = shader 操作栈顺序）。
     *
     * <p>35 个标量通道走 {@link KeyframeInterpolator#interpolateChannel}（缺省 0 = 无效果）；
     * 曲线组（形态 b）的十条曲线都是 clip 级字段（{@code curve} / {@code r_curve} / {@code g_curve} /
     * {@code b_curve} 与六条 hue 曲线 {@code hv_h_curve} ~ {@code sv_l_curve}，不随时间变）：
     * LUT 由 {@link ColorCurve} 对象持有（按 clip 缓存，逐帧拿到同一个数组），
     * 此处只把各自的强度在本地时间处插值——<b>强度缺省 1</b>：写了曲线就是全量生效，
     * 关键帧把强度写回 0 即曲线淡出；无曲线时该强度置 0（那一步不生效）。</p>
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
        // LUT（clip 级文件名 + 关键帧强度）：解析结果按路径缓存、按 clip 记引用（失败 = null = 无 LUT）；
        // 无 LUT 时强度置 0（那一步不生效），有 LUT 时强度缺省 1 = 全量生效
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
                // PS 式六色带微调（第 17 步）：六条固定色带（红 / 黄 / 绿 / 青 / 蓝 / 品红）各一对
                // hue（±1 = ±180° 旋转）/ sat（乘性 (1 + Δsat)）通道，缺省 0 = 无效果
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
                // 层级混合（第 20 步 = 全部操作栈步骤之后整体施加）：clip 级 blend_mode 编码
                // + 关键帧 blend_amount（缺省 1 = 全量生效）；只有 ADJUST 轨（master）带混合字段——
                // lane 路径恒得缺省（normal + 1 = 混合步骤恒等，行为与混合功能落地前逐位一致）
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
