package com.immersivecinematics.immersive_cinematics.client.post;

/**
 * master / lane 画面颜色调整的参数快照（第一批标量组 + R/G/B 通道系数 + 完整 HSL + RGB 复合曲线
 * + 每通道曲线 + 六条 hue 曲线）。
 *
 * <p>分量顺序 = 着色器里的操作栈顺序（{@code assets/minecraft/shaders/core/ic_color_adjust.fsh}），
 * 也是 {@code AdjustTrackPlayer} 逐通道插值的顺序；除十条曲线 LUT 外的通道都是<b>关键帧字段</b>
 * （字段名 / 缺省 / 范围见 {@code docs/SCRIPT_FORMAT.md} §10 与
 * {@code script/schema/TrackSchemas.adjust()}）。</p>
 *
 * <h2>口径：全部为「0 = 无效果」的增量（曲线强度除外）</h2>
 * 17 个标量通道的缺省值都是 {@code 0}，{@link #IDENTITY} = 全部缺省 = 画面不变
 * （渲染侧连 pass 都不开，见 {@link ColorAdjustPass}）。所以：
 * <ul>
 *   <li>HSL 组（{@code hue / saturation / vibrance / lightness}）在同一块里换算，顺序
 *       hue 旋转 → 饱和度 / 自然饱和度 → 亮度：{@code hue = ±1} 旋转 ±180°、
 *       {@code saturation = -1} 全灰 / {@code = 1} 双倍、{@code lightness = 1} 全白 /
 *       {@code = -1} 全黑（四个值全 0 且六条 hue 曲线都不生效时整块跳过，逐位恒等）；</li>
 *   <li>{@code red / green / blue} 是逐通道乘性系数（实际增益 = {@code 1 + 值}：
 *       {@code -1} = 该通道归零、{@code -0.5} = 减半、{@code +1} = 双倍）；</li>
 *   <li>{@code grayscale / invert} 本身就是 0~1 的混合强度；</li>
 *   <li>关键帧把某通道写回 0 即「该工具淡出」，不需要额外的 enabled 开关。</li>
 * </ul>
 *
 * <h2>曲线组（形态 b）：复合曲线 + 每通道曲线</h2>
 * 曲线本身都是 clip 级字段（不随时间变），关键帧只控各自的混合强度：
 * <ul>
 *   <li>{@link #curveLut} = RGB 复合曲线（clip 级 {@code curve}，四个通道共用一条）、
 *       {@link #rCurveLut} / {@link #gCurveLut} / {@link #bCurveLut} = 每通道曲线
 *       （clip 级 {@code r_curve} / {@code g_curve} / {@code b_curve}）——都是 CPU 侧采样好的 256 点 LUT
 *       （{@code null} = 本片段没有那条曲线），按 clip 缓存（同一 clip 每帧拿到的是同一个数组，
 *       渲染侧按引用比较、只在换曲线时重传纹理）；</li>
 *   <li>{@link #curveStrength} / {@link #rCurveStrength} / {@link #gCurveStrength} / {@link #bCurveStrength}
 *       = 各自曲线的混合强度（{@code c = mix(c, lut(c), strength)}，0~1），<b>缺省 1</b>——写了曲线就是
 *       全量生效，关键帧把强度写回 0 即「曲线淡出」；</li>
 *   <li>没有对应曲线（LUT 为 {@code null}）时该强度被忽略、那一步不生效；</li>
 *   <li>操作栈位置：复合曲线在前（RGB 通道系数之后），每通道曲线紧随其后（HSL 块之前）——
 *       每通道曲线看到的是复合曲线处理后的值。</li>
 * </ul>
 *
 * <h2>六条 hue 曲线（DaVinci 曲线页口径）</h2>
 * 六条曲线同样都是 clip 级字段（不随时间变），关键帧只控各自的混合强度；全部在 <b>HSL 块内</b>、
 * 标量 HSL（hue / saturation / vibrance / lightness）之后、灰度之前生效，共用同一次 {@code rgb2hsl}
 * 换算，<b>键取进入该块时的 {@code h0} / {@code s0} / {@code l0}</b>（不受标量块改动影响）：
 * <table border="1">
 *   <caption>六条曲线的键 / 目标 / 公式（{@code st} = 各自强度，顺序即下表的顺序）</caption>
 *   <tr><th>曲线</th><th>键</th><th>目标</th><th>公式</th></tr>
 *   <tr><td>HvH（{@link #hvHLut}）</td><td>hue</td><td>hue</td>
 *       <td>{@code h = mix(h, HvH(h0), st)}</td></tr>
 *   <tr><td>HvS（{@link #hvSLut}）</td><td>hue</td><td>饱和度</td>
 *       <td>{@code s = mix(s, HvS(h0), st)}</td></tr>
 *   <tr><td>HvL（{@link #hvLLut}）</td><td>hue</td><td>亮度</td>
 *       <td>{@code l = mix(l, HvL(h0), st)}</td></tr>
 *   <tr><td>LvS（{@link #lvSLut}）</td><td>亮度</td><td>饱和度</td>
 *       <td>{@code s = mix(s, LvS(l0), st)}</td></tr>
 *   <tr><td>SvS（{@link #svSLut}）</td><td>饱和度</td><td>饱和度</td>
 *       <td>{@code s = mix(s, SvS(s0), st)}</td></tr>
 *   <tr><td>SvL（{@link #svLLut}）</td><td>饱和度</td><td>亮度</td>
 *       <td>{@code l = mix(l, SvL(s0), st)}</td></tr>
 * </table>
 * 同一目标分量的多条按表序依次叠加；{@code null} = 本片段没有那条曲线（该强度被忽略、那一步不生效）。
 *
 * <p>不可变值对象：每帧由 {@code script.AdjustTrackPlayer} 重新采样一份，
 * 渲染侧只读，不做原地修改（十条 LUT 指向的数组同样只读）。</p>
 */
public record ColorAdjustParams(
        float exposure,
        float contrast,
        float highlights,
        float shadows,
        float whites,
        float blacks,
        float hue,
        float saturation,
        float vibrance,
        float lightness,
        float temperature,
        float tint,
        float red,
        float green,
        float blue,
        float[] curveLut,
        float[] rCurveLut,
        float[] gCurveLut,
        float[] bCurveLut,
        float curveStrength,
        float rCurveStrength,
        float gCurveStrength,
        float bCurveStrength,
        float[] hvHLut,
        float[] hvSLut,
        float[] hvLLut,
        float[] lvSLut,
        float[] svSLut,
        float[] svLLut,
        float hvHStrength,
        float hvSStrength,
        float hvLStrength,
        float lvSStrength,
        float svSStrength,
        float svLStrength,
        float grayscale,
        float invert) {

    /** 恒等参数（全部缺省）：画面逐位不变。 */
    public static final ColorAdjustParams IDENTITY =
            new ColorAdjustParams(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, null, null, null, null,
                    0.0F, 0.0F, 0.0F, 0.0F,
                    null, null, null, null, null, null,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

    /**
     * 是否恒等：17 个标量通道全为缺省 0 <b>且</b>十条曲线都「不存在或强度为 0」。
     * <p>恒等 = 不产生任何画面差异（渲染侧第一行就返回）。</p>
     */
    public boolean isIdentity() {
        return exposure == 0.0F && contrast == 0.0F && highlights == 0.0F && shadows == 0.0F
                && whites == 0.0F && blacks == 0.0F && hue == 0.0F && saturation == 0.0F
                && vibrance == 0.0F && lightness == 0.0F
                && temperature == 0.0F && tint == 0.0F
                && red == 0.0F && green == 0.0F && blue == 0.0F
                && (curveLut == null || curveStrength == 0.0F)
                && (rCurveLut == null || rCurveStrength == 0.0F)
                && (gCurveLut == null || gCurveStrength == 0.0F)
                && (bCurveLut == null || bCurveStrength == 0.0F)
                && (hvHLut == null || hvHStrength == 0.0F)
                && (hvSLut == null || hvSStrength == 0.0F)
                && (hvLLut == null || hvLStrength == 0.0F)
                && (lvSLut == null || lvSStrength == 0.0F)
                && (svSLut == null || svSStrength == 0.0F)
                && (svLLut == null || svLStrength == 0.0F)
                && grayscale == 0.0F && invert == 0.0F;
    }
}
