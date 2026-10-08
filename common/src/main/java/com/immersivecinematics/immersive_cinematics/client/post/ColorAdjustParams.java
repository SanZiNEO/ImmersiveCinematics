package com.immersivecinematics.immersive_cinematics.client.post;

/**
 * master 画面颜色调整的参数快照（第一批标量组 + R/G/B 通道系数 + 完整 HSL：共 17 通道）。
 *
 * <p>分量顺序 = 着色器里的操作栈顺序（{@code assets/minecraft/shaders/core/ic_color_adjust.fsh}），
 * 也是 {@code AdjustTrackPlayer} 逐通道插值的顺序；全部是<b>关键帧字段</b>
 * （字段名 / 缺省 / 范围见 {@code docs/SCRIPT_FORMAT.md} §10 与
 * {@code script/schema/TrackSchemas.adjust()}）。</p>
 *
 * <h2>口径：全部为「0 = 无效果」的增量</h2>
 * 17 个通道的缺省值都是 {@code 0}，{@link #IDENTITY} = 全部缺省 = 画面不变
 * （渲染侧连 pass 都不开，见 {@link ColorAdjustPass}）。所以：
 * <ul>
 *   <li>HSL 组（{@code hue / saturation / vibrance / lightness}）在同一块里换算，顺序
 *       hue 旋转 → 饱和度 / 自然饱和度 → 亮度：{@code hue = ±1} 旋转 ±180°、
 *       {@code saturation = -1} 全灰 / {@code = 1} 双倍、{@code lightness = 1} 全白 /
 *       {@code = -1} 全黑（四个值全 0 时整块跳过，逐位恒等）；</li>
 *   <li>{@code red / green / blue} 是逐通道乘性系数（实际增益 = {@code 1 + 值}：
 *       {@code -1} = 该通道归零、{@code -0.5} = 减半、{@code +1} = 双倍）；</li>
 *   <li>{@code grayscale / invert} 本身就是 0~1 的混合强度；</li>
 *   <li>关键帧把某通道写回 0 即「该工具淡出」，不需要额外的 enabled 开关。</li>
 * </ul>
 *
 * <p>不可变值对象：每帧由 {@code script.AdjustTrackPlayer} 重新采样一份，
 * 渲染侧只读，不做原地修改。</p>
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
        float grayscale,
        float invert) {

    /** 恒等参数（全部缺省）：画面逐位不变。 */
    public static final ColorAdjustParams IDENTITY =
            new ColorAdjustParams(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

    /** 是否恒等（17 通道全为缺省 0）：恒等 = 不产生任何画面差异。 */
    public boolean isIdentity() {
        return exposure == 0.0F && contrast == 0.0F && highlights == 0.0F && shadows == 0.0F
                && whites == 0.0F && blacks == 0.0F && hue == 0.0F && saturation == 0.0F
                && vibrance == 0.0F && lightness == 0.0F
                && temperature == 0.0F && tint == 0.0F
                && red == 0.0F && green == 0.0F && blue == 0.0F
                && grayscale == 0.0F && invert == 0.0F;
    }
}
