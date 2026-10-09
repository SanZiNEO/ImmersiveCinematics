package com.immersivecinematics.immersive_cinematics.client.post;

import com.immersivecinematics.immersive_cinematics.script.CubeLut;

/**
 * master / lane 画面颜色调整的参数快照（第一批标量组 + R/G/B 通道系数 + RGB 通道混合器 + 完整 HSL
 * + RGB 复合曲线 + 每通道曲线 + 六条 hue 曲线 + Lift / Gamma / Gain 色轮 + PS 式六色带微调）。
 *
 * <p>分量顺序 = 着色器里的操作栈顺序（{@code assets/minecraft/shaders/core/ic_color_adjust.fsh}），
 * 也是 {@code AdjustTrackPlayer} 逐通道插值的顺序；除十条曲线 LUT 外的通道都是<b>关键帧字段</b>
 * （字段名 / 缺省 / 范围见 {@code docs/SCRIPT_FORMAT.md} §10 与
 * {@code script/schema/TrackSchemas.adjust()}）。</p>
 *
 * <h2>口径：全部为「0 = 无效果」的增量（曲线强度除外）</h2>
 * 47 个标量通道的缺省值都是 {@code 0}，{@link #IDENTITY} = 全部缺省 = 画面不变
 * （渲染侧连 pass 都不开，见 {@link ColorAdjustPass}）。所以：
 * <ul>
 *   <li>HSL 组（{@code hue / saturation / vibrance / lightness}）在同一块里换算，顺序
 *       hue 旋转 → 饱和度 / 自然饱和度 → 亮度：{@code hue = ±1} 旋转 ±180°、
 *       {@code saturation = -1} 全灰 / {@code = 1} 双倍、{@code lightness = 1} 全白 /
 *       {@code = -1} 全黑（四个值全 0 且六条 hue 曲线都不生效时整块跳过，逐位恒等）；</li>
 *   <li>{@code red / green / blue} 是逐通道乘性系数（实际增益 = {@code 1 + 值}：
 *       {@code -1} = 该通道归零、{@code -0.5} = 减半、{@code +1} = 双倍）；</li>
 *   <li>{@code mixRR} ~ {@code mixBB} 是 <b>RGB 通道混合器</b>（PS 式 3×3 矩阵）：实际矩阵 =
 *       单位阵 + 参数矩阵，行 = 输出通道、列 = 输入通道 ——
 *       {@code out.r = (1+mixRR)·r + mixRG·g + mixRB·b}，g / b 行同式
 *       （例：{@code mixRR = -1} 把红通道归零、{@code mixRG = 1} 把绿并进红）；
 *       九个全 0 = 单位阵 = 逐位恒等（跳过整个矩阵乘法）；</li>
 *   <li>{@code liftR} ~ {@code gainB} 是 <b>Lift / Gamma / Gain 色轮</b>（三组逐通道，
 *       位置 = 每通道曲线之后、钳制 {@code [0,1]} 之前）：
 *       Lift（阴影）{@code c' = c + lift·(1-c)}——正 = 抬阴影、负 = 压黑，{@code c = 1} 不动；
 *       Gamma（中间调）{@code c' = pow(max(c,0), exp2(-gamma))}——{@code 0} = 指数 1 恒等，
 *       正 = 中间调提亮、负 = 压暗；
 *       Gain（高光）{@code c' = c·(1+gain)}——正 = 乘性提亮、负 = 压暗；
 *       九个全 0 = 整步跳过（逐位恒等），结果与前面的步骤同受那一次钳制管辖；</li>
 *   <li>{@link #lut} / {@link #lutStrength} 是 <b>LUT</b>（clip 级 {@code lut} 文件名 + 关键帧
 *       {@code lut_strength}）：位置 = LGG 之后、HSL 块之前，{@code c = mix(c, lut(c), strength)}
 *       （{@code lut_strength} 缺省 1 = 全量生效，写回 0 = 淡出）；LUT 为空（{@code null}）或强度 0 =
 *       整步跳过（逐位恒等）；</li>
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
 *   <li>操作栈位置：复合曲线在前（RGB 通道系数 / 通道混合器之后），每通道曲线紧随其后（HSL 块之前）——
 *       每通道曲线看到的是复合曲线处理后的值。</li>
 * </ul>
 *
 * <h2>LUT（clip 级 {@code lut} / {@code lut_input_gamma} + 关键帧 {@code lut_strength}）</h2>
 * {@link #lut} = clip 级文件名（{@code resource/} 目录下的 {@code .cube}，由 {@code CubeLutLoader}
 * 解析成不可变 {@link CubeLut} 实例、按路径缓存），{@link #lutStrength} = 关键帧插值的混合强度
 * （<b>缺省 1</b>，0 = 淡出），{@link #lutInputGamma} = clip 级输入域适配的幂指数
 * （<b>缺省 1 = 不变换</b>，必须 &gt; 0）。LUT 是<b>整体画面（master）</b>处理：只写在 <b>ADJUST 轨</b>的片段上
 * （相机片段不带 {@code lut}，lane 级采样拿到的 {@link #lut} 恒为 {@code null}）。
 * 采样口径（与 {@link CubeLut} 的文档一致）：
 * <ul>
 *   <li><b>查表输入</b> = {@code v = pow(clamp(c, 0, 1), lutInputGamma)}（逐通道；缺省 1 = 不变换，
 *       渲染侧不执行 {@code pow}、逐位恒等）——现成 .cube 按特定素材 / 色彩空间调成时，
 *       用它把游戏画面映射回表假设的输入域；{@code lut == null} 时该值被忽略（整步不生效）；</li>
 *   <li><b>1D + 3D 组合</b>（Resolve shaper 形态）：<b>1D 先、输出喂 3D</b>；只有 1D 或只有 3D 时走单段；</li>
 *   <li>查表坐标 = {@code (v - min) / (max - min) * (size - 1)} 后钳制到 {@code [0, size-1]}，
 *       边界钳到端点（clamp-to-edge）；{@code min} / {@code max} 取该 LUT 的 DOMAIN（逐通道）；</li>
 *   <li>插值：3D 段 = <b>四面体</b>（tetrahedral；着色器 {@code texelFetch} 取 8 个角点后自己加权，
 *       不用硬件三线性——三线性在中性灰附近会偏色，见 {@code example/lut-reference/README.md}）；
 *       1D 段 = 相邻采样点之间的线性插值；</li>
 *   <li>混合：{@code c = mix(c, lut(pow(c, lutInputGamma)), clamp(lutStrength, 0, 1))}——混合的是
 *       <b>原值</b>与查表结果，在 LUT 采样之后做；</li>
 *   <li>输出<b>不额外钳制</b>（LUT 数据本身可超出 {@code [0,1]}，与曲线同段、由 HSL 块前那一次
 *       {@code clamp(c, 0, 1)} 兜底）。</li>
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
 * <h2>PS 式六色带微调（Hue / Sat 分色带）</h2>
 * 12 个关键帧通道 = 六条固定色带（红 / 黄 / 绿 / 青 / 蓝 / 品红，中心角 0° / 60° / 120° / 180° /
 * 240° / 300°）各一对：{@code hue_*} = 该带内的色相旋转（{@code ±1} = ±180°）、
 * {@code sat_*} = 该带内的饱和度乘性增量（{@code sat *= 1 + Δsat}，钳制 0~1）；各 {@code -1 ~ 1}、
 * 缺省 {@code 0} = 无效果。带权重 = 升余弦锥形
 * {@code w = 0.5·(1 + cos(π·|Δ| / 30°))}（{@code |Δ|} = 像素色相与中心的最短角距、单位度；
 * {@code |Δ| ≥ 30° → 0}）——六带各撑 ±30°、恰铺满色相环，相邻带在边界平滑交叠。
 * 像素级结果：{@code Δhue = Σ wᵢ·hueᵢ}、{@code Δsat = Σ wᵢ·satᵢ}，<b>先旋色相、再改饱和</b>
 * （{@code h' = fract(h + 0.5·Δhue)}、{@code s' = clamp(s·(1 + Δsat), 0, 1)}）；
 * 十二通道全 0 = 整步跳过（逐位恒等）。栈位 = HSL 块（步骤 12 ~ 16）之后、灰度之前（第 17 步）。
 * <p>透明像素不变量：{@code alpha = 0} 的像素（RGB = 黑）经本步任意参数后 RGB 仍为 {@code (0,0,0)}
 * ——{@code s = 0} / {@code l = 0} 走 {@code hsl2rgb} 的灰度分支，色相旋转不产生新值、不复活透明像素。</p>
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
        float mixRR,
        float mixRG,
        float mixRB,
        float mixGR,
        float mixGG,
        float mixGB,
        float mixBR,
        float mixBG,
        float mixBB,
        float[] curveLut,
        float[] rCurveLut,
        float[] gCurveLut,
        float[] bCurveLut,
        float curveStrength,
        float rCurveStrength,
        float gCurveStrength,
        float bCurveStrength,
        float liftR,
        float liftG,
        float liftB,
        float gammaR,
        float gammaG,
        float gammaB,
        float gainR,
        float gainG,
        float gainB,
        CubeLut lut,
        float lutStrength,
        float lutInputGamma,
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
        float hueRed,
        float satRed,
        float hueYellow,
        float satYellow,
        float hueGreen,
        float satGreen,
        float hueCyan,
        float satCyan,
        float hueBlue,
        float satBlue,
        float hueMagenta,
        float satMagenta,
        float grayscale,
        float invert) {

    /** 恒等参数（全部缺省）：画面逐位不变。 */
    public static final ColorAdjustParams IDENTITY =
            new ColorAdjustParams(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    null, null, null, null,
                    0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    null, 0.0F, 1.0F,
                    null, null, null, null, null, null,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F);

    /**
     * 是否恒等：47 个标量通道全为缺省 0、<b>且</b>十条曲线都「不存在或强度为 0」、
     * <b>且</b>无 LUT（{@link #lut} 为 {@code null}）或 LUT 强度为 0。
     * <p>恒等 = 不产生任何画面差异（渲染侧第一行就返回）。</p>
     */
    public boolean isIdentity() {
        return exposure == 0.0F && contrast == 0.0F && highlights == 0.0F && shadows == 0.0F
                && whites == 0.0F && blacks == 0.0F && hue == 0.0F && saturation == 0.0F
                && vibrance == 0.0F && lightness == 0.0F
                && temperature == 0.0F && tint == 0.0F
                && red == 0.0F && green == 0.0F && blue == 0.0F
                && mixRR == 0.0F && mixRG == 0.0F && mixRB == 0.0F
                && mixGR == 0.0F && mixGG == 0.0F && mixGB == 0.0F
                && mixBR == 0.0F && mixBG == 0.0F && mixBB == 0.0F
                && (curveLut == null || curveStrength == 0.0F)
                && (rCurveLut == null || rCurveStrength == 0.0F)
                && (gCurveLut == null || gCurveStrength == 0.0F)
                && (bCurveLut == null || bCurveStrength == 0.0F)
                && liftR == 0.0F && liftG == 0.0F && liftB == 0.0F
                && gammaR == 0.0F && gammaG == 0.0F && gammaB == 0.0F
                && gainR == 0.0F && gainG == 0.0F && gainB == 0.0F
                && (lut == null || lutStrength == 0.0F)
                && (hvHLut == null || hvHStrength == 0.0F)
                && (hvSLut == null || hvSStrength == 0.0F)
                && (hvLLut == null || hvLStrength == 0.0F)
                && (lvSLut == null || lvSStrength == 0.0F)
                && (svSLut == null || svSStrength == 0.0F)
                && (svLLut == null || svLStrength == 0.0F)
                && hueRed == 0.0F && satRed == 0.0F
                && hueYellow == 0.0F && satYellow == 0.0F
                && hueGreen == 0.0F && satGreen == 0.0F
                && hueCyan == 0.0F && satCyan == 0.0F
                && hueBlue == 0.0F && satBlue == 0.0F
                && hueMagenta == 0.0F && satMagenta == 0.0F
                && grayscale == 0.0F && invert == 0.0F;
    }
}
