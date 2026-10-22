package com.immersivecinematics.immersive_cinematics.client.post;

import com.immersivecinematics.immersive_cinematics.script.CubeLut;

/**
 * master / lane 画面颜色调整的参数快照（标量组 + 通道系数 / 混合器 + 曲线 + LUT + 六色带 + 层级混合）。
 * 分量顺序 = 着色器操作栈顺序（{@code assets/minecraft/shaders/core/ic_color_adjust.fsh}）；
 * 除曲线 LUT 数组与 clip 级字段（{@code lut} / {@code lutInputGamma} / {@code blendMode}）外，其余通道都是关键帧字段。
 *
 * 47 个标量通道缺省 {@code 0} = 无效果，关键帧写回 {@code 0} 即该工具淡出；{@link #IDENTITY} = 全部缺省。
 * 六色带 12 通道（{@code hue_*} / {@code sat_*}）各 {@code -1 ~ 1}、缺省 {@code 0}，先旋色相再改饱和，
 * 权重公式见 fsh 的 {@code sixBandWeight}。曲线强度缺省 {@code 1}，无对应曲线时被忽略。
 * LUT：{@link #lut} = clip 级 {@code .cube} 文件名（{@code null} = 无 LUT），
 * {@link #lutStrength} 缺省 {@code 1}，{@link #lutInputGamma} 缺省 {@code 1}（不变换）且必须 {@code > 0}；
 * 采样口径见 {@link CubeLut} 文档。
 * 层级混合：{@link #blendMode} 编码 {@code 0 ~ 4}、缺省 {@code normal}；
 * {@link #blendAmount} {@code 0 ~ 1}、缺省 {@code 1}。
 *
 * 不可变值对象：每帧由 {@code script.AdjustTrackPlayer} 重新采样一份，渲染侧只读（LUT 数组同样只读）。
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
        float invert,
        float blendMode,
        float blendAmount) {

    /**
     * 混合模式编码（= 着色器 {@code BlendMode} uniform）：0 = {@code normal}（直替换，缺省）、
     * 1 = {@code multiply}、2 = {@code screen}、3 = {@code soft_light}、4 = {@code overlay}。
     */
    public static final float BLEND_NORMAL = 0.0F;
    public static final float BLEND_MULTIPLY = 1.0F;
    public static final float BLEND_SCREEN = 2.0F;
    public static final float BLEND_SOFT_LIGHT = 3.0F;
    public static final float BLEND_OVERLAY = 4.0F;

    /**
     * {@code blend_mode} 枚举名 → 编码（{@link #BLEND_NORMAL} ~ {@link #BLEND_OVERLAY}）；
     * 未知 / {@code null} 归 {@code normal}。
     */
    public static float blendModeCode(String mode) {
        if (mode == null) {
            return BLEND_NORMAL;
        }
        return switch (mode) {
            case "multiply" -> BLEND_MULTIPLY;
            case "screen" -> BLEND_SCREEN;
            case "soft_light" -> BLEND_SOFT_LIGHT;
            case "overlay" -> BLEND_OVERLAY;
            default -> BLEND_NORMAL;
        };
    }

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
                    0.0F, 0.0F, BLEND_NORMAL, 1.0F);

    /**
     * 是否恒等：47 个标量通道全为 {@code 0}、十条曲线都不存在或强度为 {@code 0}、无 LUT 或 LUT 强度为 {@code 0}，
     * 且层级混合不改变画面（{@link #blendMode} = {@link #BLEND_NORMAL} 或 {@link #blendAmount} = {@code 0}）。
     * 恒等 = 不产生任何画面差异（渲染侧第一行返回）。
     */
    public boolean isIdentity() {
        if (blendAmount == 0.0F) {
            return true;    // 调整层整体透明：输出 = 基画面 = pass 输入（不产生任何画面差异）
        }
        if (blendMode != BLEND_NORMAL) {
            return false;   // 非 normal：混合结果 ≠ 基画面（即使操作栈恒等）→ 一定非恒等
        }
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
