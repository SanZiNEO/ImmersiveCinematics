package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;
import java.util.Map;

/**
 * fade 覆盖层的高级颜色遮罩定义——解析期一次建成的不可变快照，无 MC / GL 依赖。
 *
 * 数据来源 = 已解析的 {@link Clip}：clip 级 {@code color} / {@code gradient} / {@code mask_shape} /
 * {@code blend_mode}，关键帧级 {@code color}（纯色模式逐 RGB 线性插值）；只做数据归一与缓存，
 * 不向脚本 map 写值、不改写入参。
 *
 * {@code color} = {@code #RRGGBB}，缺省黑色（{@code #} 可省、大小写均可、恰 6 位，不支持 ARGB）。
 * {@code gradient} = 可选 {@code {type: linear|radial, stops: [{offset, color}, ...]}}：至少 2 个色标、
 * offset 有限且 0~1、严格递增，首尾不必 0 / 1（范围外保持端点），不设数量上限；缺省 = 纯色。
 * {@code mask_shape} = {@code full}（缺省）/ {@code rect} / {@code circle}；{@code blend_mode} =
 * {@code normal}（缺省）/ {@code multiply} / {@code screen} / {@code soft_light} / {@code overlay}。
 *
 * 纯色关键帧颜色在 {@link #from(Clip)} 里一次解析成 RGB int 缓存；{@link #sampleColor(float)} 只做段内
 * 逐通道线性插值与端点保持，不逐帧 parse 字符串、不分配数组。渐变 stops 同样只解析一次。
 * 无颜色动画（无关键帧声明 {@code color}）或使用渐变时不建颜色缓存，共享空数组，采样直接返回 clip 级颜色。
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

    /** 共享空数组：无渐变 / 无颜色关键帧时不分配结构数组（访问器返回副本，调用方无法改动）。 */
    private static final float[] EMPTY_OFFSETS = new float[0];
    private static final int[] EMPTY_COLORS = new int[0];

    private final int baseColor;
    private final boolean hasGradient;
    private final String gradientType;
    private final float[] stopOffsets;
    private final int[] stopColors;
    private final String maskShape;
    private final String blendMode;

    /** 关键帧时间（秒，片段内）；与 {@link #keyframeColors} 同长、逐项对应。 */
    private final float[] keyframeTimes;
    /** 每个关键帧解析后的 0xRRGGBB；关键帧没写 {@code color} 时已填成 {@link #baseColor}。 */
    private final int[] keyframeColors;

    private ColorMaskSpec(int baseColor, boolean hasGradient, String gradientType,
                          float[] stopOffsets, int[] stopColors, String maskShape, String blendMode,
                          float[] keyframeTimes, int[] keyframeColors) {
        this.baseColor = baseColor;
        this.hasGradient = hasGradient;
        this.gradientType = gradientType;
        this.stopOffsets = stopOffsets;
        this.stopColors = stopColors;
        this.maskShape = maskShape;
        this.blendMode = blendMode;
        this.keyframeTimes = keyframeTimes;
        this.keyframeColors = keyframeColors;
    }

    /**
     * 从已解析的片段建立定义（解析期一次；结果由 {@link Clip#getColorMaskSpec()} 懒缓存）。
     * 只读 {@link Clip#getData()} 与 {@link Clip#getKeyframes()}，不改任何入参结构。
     * 结构非法（渐变类型 / stops / offset / 颜色、mask_shape、blend_mode、关键帧颜色）时抛
     * {@link InvalidColorMaskException}（携带 clip 内相对字段路径），由 {@code ScriptParser} 转成
     * 带完整脚本路径的解析错误——不把非法输入默默变黑。
     */
    public static ColorMaskSpec from(Clip clip) {
        if (clip == null) {
            throw new IllegalArgumentException("clip 不能为 null");
        }
        Map<String, Object> data = clip.getData();
        int baseColor = parseBaseColor(data);
        Gradient gradient = parseGradient(data.get("gradient"));
        String maskShape = parseMaskShape(data);
        String blendMode = parseBlendMode(data);

        List<Keyframe> keyframes = clip.getKeyframes();
        // 只有「纯色 + 至少一个关键帧显式声明 color」才建颜色时间 / 颜色缓存；其余共享空数组。
        // 显式 keyframe.color 仍逐项检查格式：渐变模式不消费，也不能吞掉格式错误。
        boolean declaresKeyframeColor = false;
        for (Keyframe keyframe : keyframes) {
            if (keyframe.getData().get("color") != null) {
                declaresKeyframeColor = true;
                break;
            }
        }

        float[] times;
        int[] colors;
        if (gradient != null || !declaresKeyframeColor) {
            for (int i = 0; i < keyframes.size(); i++) {
                Object raw = keyframes.get(i).getData().get("color");
                if (raw != null) {
                    parseKeyframeColor(raw, i, baseColor); // 只验证，不缓存
                }
            }
            times = EMPTY_OFFSETS;
            colors = EMPTY_COLORS;
        } else {
            times = new float[keyframes.size()];
            colors = new int[keyframes.size()];
            for (int i = 0; i < keyframes.size(); i++) {
                Keyframe keyframe = keyframes.get(i);
                times[i] = keyframe.getTime();
                colors[i] = parseKeyframeColor(keyframe.getData().get("color"), i, baseColor);
            }
        }

        return new ColorMaskSpec(baseColor,
                gradient != null, gradient != null ? gradient.type() : "linear",
                gradient != null ? gradient.offsets() : EMPTY_OFFSETS,
                gradient != null ? gradient.colors() : EMPTY_COLORS,
                maskShape, blendMode, times, colors);
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

    /**
     * 纯色模式颜色采样（0xRRGGBB）：关键帧 {@code color} 已预解析，段内逐 RGB 通道线性插值；
     * 首帧之前 / 末帧之后保持端点；零长段取左端点；关键帧没写 {@code color} 时使用 clip 级颜色。
     * 无关键帧 / 渐变模式 → clip 级颜色（渐变颜色来自 stops，渲染侧不消费本方法返回值）。
     * 只读缓存数组，不分配、不 parse 字符串，可逐帧调用。
     */
    public int sampleColor(float localTime) {
        if (hasGradient || keyframeColors.length == 0) {
            return baseColor;
        }
        if (keyframeColors.length == 1) {
            return keyframeColors[0];
        }
        if (!Float.isFinite(localTime)) {
            return baseColor;
        }

        int segment = -1;
        for (int i = 0; i < keyframeTimes.length - 1; i++) {
            if (localTime >= keyframeTimes[i] && localTime <= keyframeTimes[i + 1]) {
                segment = i;
                break;
            }
        }
        if (segment < 0) {
            return localTime < keyframeTimes[0]
                    ? keyframeColors[0]
                    : keyframeColors[keyframeColors.length - 1];
        }

        float span = keyframeTimes[segment + 1] - keyframeTimes[segment];
        float t = span > 0.001f ? (localTime - keyframeTimes[segment]) / span : 0f;
        t = Math.max(0f, Math.min(1f, t));
        return lerpRgb(keyframeColors[segment], keyframeColors[segment + 1], t);
    }

    // ========== 解析 ==========

    private static int parseBaseColor(Map<String, Object> data) {
        Object raw = data.get("color");
        if (raw == null) {
            return 0x000000;
        }
        if (!(raw instanceof String text)) {
            throw new InvalidColorMaskException("color",
                    "需要字符串颜色（#RRGGBB 六位十六进制，# 可省）");
        }
        return parseColor(text, "color");
    }

    private static String parseMaskShape(Map<String, Object> data) {
        Object raw = data.get("mask_shape");
        if (raw == null) {
            return DEFAULT_MASK_SHAPE;
        }
        if (!(raw instanceof String text)) {
            throw new InvalidColorMaskException("mask_shape",
                    "需要字符串（full / rect / circle；缺省 full）");
        }
        if (!contains(MASK_SHAPES, text)) {
            throw new InvalidColorMaskException("mask_shape",
                    "未知覆盖形状: " + text + "（可选: full / rect / circle）");
        }
        return text;
    }

    private static String parseBlendMode(Map<String, Object> data) {
        Object raw = data.get("blend_mode");
        if (raw == null) {
            return DEFAULT_BLEND_MODE;
        }
        if (!(raw instanceof String text)) {
            throw new InvalidColorMaskException("blend_mode",
                    "需要字符串（normal / multiply / screen / soft_light / overlay；缺省 normal）");
        }
        if (!contains(BLEND_MODES, text)) {
            throw new InvalidColorMaskException("blend_mode",
                    "未知混合模式: " + text
                            + "（可选: normal / multiply / screen / soft_light / overlay）");
        }
        return text;
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

    /** 一个关键帧的 {@code color}：缺省回落 clip 级颜色；显式值必须是合法 {@code #RRGGBB}。 */
    private static int parseKeyframeColor(Object raw, int index, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String text)) {
            throw new InvalidColorMaskException("keyframes[" + index + "].color",
                    "需要字符串颜色（#RRGGBB 六位十六进制，# 可省）");
        }
        return parseColor(text, "keyframes[" + index + "].color");
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

    /** 0xRRGGBB 逐通道线性插值（四舍五入到最近整数；只在段内调用）。 */
    private static int lerpRgb(int from, int to, float t) {
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = lerpChannel(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        int value = Math.round(from + (to - from) * t);
        return Math.max(0, Math.min(255, value));
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
