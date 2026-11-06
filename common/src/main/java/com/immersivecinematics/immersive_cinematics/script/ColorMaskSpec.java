package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;
import java.util.Map;

/**
 * fade 覆盖层的高级颜色遮罩定义——解析期一次建成的不可变快照，无 MC / GL 依赖。
 *
 * 数据来源 = 片段的对象模型 value-only 字段：clip 级 {@code color} / {@code gradient} /
 * {@code mask_shape} / {@code blend_mode}；只做数据归一与缓存，不向脚本 map 写值、不改写入参。
 * 纯色由 {@code color.r/g/b} 的逐帧采样值经 {@link #rgb(float, float, float)} 合成，不经过字符串解析。
 *
 * {@code color} = {@code #RRGGBB}，缺省黑色（{@code #} 可省、大小写均可、恰 6 位，不支持 ARGB）。
 * {@code gradient} = 可选 {@code {type: linear|radial, stops: [{offset, color}, ...]}}：至少 2 个色标、
 * offset 有限且 0~1、严格递增，首尾不必 0 / 1（范围外保持端点），不设数量上限；缺省 = 纯色。
 * {@code mask_shape} = {@code full}（缺省）/ {@code rect} / {@code circle}；{@code blend_mode} =
 * {@code normal}（缺省）/ {@code multiply} / {@code screen} / {@code soft_light} / {@code overlay}。
 *
 * 渐变 stops 只解析一次；无渐变时共享空数组。
 */
public final class ColorMaskSpec {

    /** 缺省颜色：黑色。 */
    public static final String DEFAULT_COLOR = "#000000";
    /** 缺省覆盖形状：整窗口。 */
    public static final String DEFAULT_MASK_SHAPE = "full";
    /** 缺省混合模式：直替换。 */
    public static final String DEFAULT_BLEND_MODE = "normal";

    private static final String[] GRADIENT_TYPES = {"linear", "radial"};
    private static final String[] MASK_SHAPES = {"full", "rect", "circle"};
    private static final String[] BLEND_MODES =
            {"normal", "multiply", "screen", "soft_light", "overlay"};

    /** 共享空数组：无渐变时不分配结构数组（访问器返回副本，调用方无法改动）。 */
    private static final float[] EMPTY_OFFSETS = new float[0];
    private static final int[] EMPTY_COLORS = new int[0];

    private final int baseColor;
    private final boolean hasGradient;
    private final String gradientType;
    private final float[] stopOffsets;
    private final int[] stopColors;
    private final String maskShape;
    private final String blendMode;

    private ColorMaskSpec(int baseColor, boolean hasGradient, String gradientType,
                          float[] stopOffsets, int[] stopColors, String maskShape, String blendMode) {
        this.baseColor = baseColor;
        this.hasGradient = hasGradient;
        this.gradientType = gradientType;
        this.stopOffsets = stopOffsets;
        this.stopColors = stopColors;
        this.maskShape = maskShape;
        this.blendMode = blendMode;
    }

    /**
     * 从片段的 value-only 字段建立定义：结构一次解析。
     *
     * <p>颜色由 {@code color.r/g/b} 的采样值经 {@link #rgb(float, float, float)} 合成。</p>
     *
     * @param baseColor      value-only {@code color} 解析出的 0xRRGGBB
     * @param gradientStruct value-only {@code gradient} 的冻结结构；{@code null} = 纯色
     * @param maskShape      {@code mask_shape}；{@code null}/空 = {@code full}
     * @param blendMode      {@code blend_mode}；{@code null}/空 = {@code normal}
     */
    public static ColorMaskSpec forObjects(int baseColor, Object gradientStruct,
                                           String maskShape, String blendMode) {
        Gradient gradient = parseGradient(gradientStruct);
        return new ColorMaskSpec(baseColor,
                gradient != null, gradient != null ? gradient.type() : "linear",
                gradient != null ? gradient.offsets() : EMPTY_OFFSETS,
                gradient != null ? gradient.colors() : EMPTY_COLORS,
                requireMaskShape(maskShape), requireBlendMode(blendMode));
    }

    /**
     * 采样颜色合成：0~1 浮点通道 → 0xRRGGBB（逐通道四舍五入并钳制；非有限值按 0）。
     * 新格式 {@code color.r/g/b} 的采样值走这里，不经过字符串解析。
     */
    public static int rgb(float red, float green, float blue) {
        return (channelByte(red) << 16) | (channelByte(green) << 8) | channelByte(blue);
    }

    private static int channelByte(float value) {
        if (!Float.isFinite(value)) {
            return 0;
        }
        int scaled = Math.round(value * 255f);
        return Math.max(0, Math.min(255, scaled));
    }

    /** 是否声明了渐变（缺省 = 纯色）。 */
    public boolean hasGradient() {
        return hasGradient;
    }

    /**
     * 渐变类型：{@code linear} / {@code radial}。
     * 无渐变时返回 {@code "linear"}（仅作稳定的非空占位；调用方应先看 {@link #hasGradient()}）。
     */
    public String gradientType() {
        return gradientType;
    }

    /**
     * 渐变色标 offset（0~1，严格递增，长度与 {@link #stopColors()} 一致）。
     * 无渐变时为空数组；返回副本，调用方无法改动定义本身。
     */
    public float[] stopOffsets() {
        return stopOffsets.clone();
    }

    /**
     * 渐变色标颜色（0xRRGGBB，与 {@link #stopOffsets()} 逐项对应）。
     * 无渐变时为空数组；返回副本。
     */
    public int[] stopColors() {
        return stopColors.clone();
    }

    /** 覆盖形状：{@code full} / {@code rect} / {@code circle}（缺省 {@code full}）。 */
    public String maskShape() {
        return maskShape;
    }

    /**
     * 混合模式：{@code normal} / {@code multiply} / {@code screen} /
     * {@code soft_light} / {@code overlay}。
     */
    public String blendMode() {
        return blendMode;
    }

    // ========== 解析 ==========

    /** {@code mask_shape} 值校验；{@code null} = 缺省 {@code full}（空串按非法值拒绝）。 */
    private static String requireMaskShape(String value) {
        if (value == null) {
            return DEFAULT_MASK_SHAPE;
        }
        if (!contains(MASK_SHAPES, value)) {
            throw new InvalidColorMaskException("mask_shape",
                    "未知覆盖形状: " + value + "（可选: full / rect / circle）");
        }
        return value;
    }

    /** {@code blend_mode} 值校验；{@code null} = 缺省 {@code normal}（空串按非法值拒绝）。 */
    private static String requireBlendMode(String value) {
        if (value == null) {
            return DEFAULT_BLEND_MODE;
        }
        if (!contains(BLEND_MODES, value)) {
            throw new InvalidColorMaskException("blend_mode",
                    "未知混合模式: " + value
                            + "（可选: normal / multiply / screen / soft_light / overlay）");
        }
        return value;
    }

    private static Gradient parseGradient(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidColorMaskException("gradient",
                    "需要对象 {type: linear|radial, stops: [...]}（缺省 = 纯色）");
        }

        Object typeRaw = map.get("type");
        if (typeRaw == null) {
            throw new InvalidColorMaskException("gradient.type",
                    "缺失（linear / radial）");
        }
        if (!(typeRaw instanceof String type) || !contains(GRADIENT_TYPES, type)) {
            throw new InvalidColorMaskException("gradient.type",
                    "需要 linear / radial（实际: " + typeRaw + "）");
        }

        Object stopsRaw = map.get("stops");
        if (!(stopsRaw instanceof List<?> stops)) {
            throw new InvalidColorMaskException("gradient.stops",
                    "需要数组 [{offset, color}, ...]（至少 2 个色标）");
        }
        if (stops.size() < 2) {
            throw new InvalidColorMaskException("gradient.stops",
                    "至少需要 2 个色标，实际: " + stops.size());
        }

        float[] offsets = new float[stops.size()];
        int[] colors = new int[stops.size()];
        float previous = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < stops.size(); i++) {
            String sp = "gradient.stops[" + i + "]";
            Object stopRaw = stops.get(i);
            if (!(stopRaw instanceof Map<?, ?> stop)) {
                throw new InvalidColorMaskException(sp, "需要对象 {offset, color}");
            }

            Object offsetRaw = stop.get("offset");
            if (!(offsetRaw instanceof Number number)) {
                throw new InvalidColorMaskException(sp + ".offset",
                        "需要有限数字（0~1）");
            }
            float offset = number.floatValue();
            if (!Float.isFinite(offset)) {
                throw new InvalidColorMaskException(sp + ".offset",
                        "不是有限数字（NaN / Infinity）：" + offsetRaw);
            }
            if (offset < 0f || offset > 1f) {
                throw new InvalidColorMaskException(sp + ".offset",
                        "超出范围 0~1：" + offset);
            }
            if (i > 0 && offset <= previous) {
                throw new InvalidColorMaskException(sp + ".offset",
                        "必须严格递增（前一个 offset = " + previous + "，当前 = " + offset + "）");
            }
            previous = offset;

            Object colorRaw = stop.get("color");
            if (!(colorRaw instanceof String colorText)) {
                throw new InvalidColorMaskException(sp + ".color",
                        "需要字符串颜色（#RRGGBB 六位十六进制，# 可省）");
            }

            offsets[i] = offset;
            colors[i] = parseColor(colorText, sp + ".color");
        }
        return new Gradient(type, offsets, colors);
    }

    /**
     * 颜色解析：与 {@code ScriptValidator#checkHexColor} / {@code FadeLayer#setColor} 的文档契约一致——
     * {@code #RRGGBB} 六位十六进制，前缀 {@code #} 可省，大小写均可；不支持 ARGB。
     */
    private static int parseColor(String text, String path) {
        if (text == null) {
            throw new InvalidColorMaskException(path, "颜色缺失");
        }
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new InvalidColorMaskException(path,
                    "颜色格式非法: \"" + text + "\"（需 #RRGGBB 六位十六进制，# 可省）");
        }
        return Integer.parseInt(hex, 16) & 0x00FFFFFF;
    }

    private static boolean contains(String[] allowed, String value) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** 解析后的渐变结构（只在本类内部使用）。 */
    private record Gradient(String type, float[] offsets, int[] colors) {}

    /**
     * 结构非法的颜色遮罩定义。{@link #getFieldPath()} 为 clip 内相对路径
     * （如 {@code gradient.stops[1].offset} / {@code keyframes[0].color}）；
     * {@code ScriptParser} 在其前面拼上片段路径后转成 {@code ScriptParseException}。
     */
    public static final class InvalidColorMaskException extends IllegalArgumentException {
        private final String fieldPath;

        public InvalidColorMaskException(String fieldPath, String message) {
            super(message);
            this.fieldPath = fieldPath;
        }

        public String getFieldPath() {
            return fieldPath;
        }
    }
}
