package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.CanvasTransform;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

/**
 * OVERLAY 片段的装配层：value-only 结构每片段解析一次，逐帧只读 {@link SampledClip} 槽位。
 *
 * <p>value-only 字段（{@code layer_type} / {@code path} / {@code text} / {@code z_index} /
 * {@code color} / {@code gradient} / {@code mask_shape} / {@code blend_mode}）由
 * {@link ClipValueView} 一次性读出并解析；{@link ColorMaskSpec} 由 {@link ColorMaskSpec#forObjects}
 * 建成，不携带关键帧颜色缓存——颜色来自 {@code color.r/g/b} 采样。</p>
 *
 * <p>逐帧装配由 {@link #compute(Clip, double, ClipRuntimeCache, OverlayClipRuntime)} 完成：一个
 * {@code SampledClip} 提供全部几何 / 颜色 / 遮罩 / 渐变采样值，按层类型只读适用槽位，其余填 schema
 * 缺省（未写该通道即用缺省）。{@code color.r/g/b} 的回落是逐通道的：只有
 * 作者显式声明了该通道（{@link ClipValueView#declared(String)}）才用采样值，否则回落 value-only
 * {@code color} 的对应通道。</p>
 *
 * <p>本类不可变（解析结果按片段身份由播放器缓存）；只在客户端主线程使用。</p>
 */
public final class OverlayClipRuntime {

    /** 层类型缺省（同 {@code TrackSchemas.overlayObjects()}）。 */
    public static final String DEFAULT_LAYER_TYPE = "fade";

    // 遮罩 / 渐变几何缺省（与 OverlayTrackPlayer 的口径一致）
    private static final float DEFAULT_MASK_POSITION = 0.5f;
    private static final float DEFAULT_MASK_SIZE = 1.0f;
    private static final float DEFAULT_MASK_RADIUS = 0.5f;
    private static final float DEFAULT_GRADIENT_ANGLE = 0f;
    private static final float DEFAULT_GRADIENT_CENTER = 0.5f;
    private static final float DEFAULT_GRADIENT_RADIUS = 0.5f;

    private static final ObjectSlotLayout LAYOUT = ObjectSlotLayout.of(TrackTypeId.OVERLAY);
    private static final int SLOT_X = LAYOUT.slotOf("x");
    private static final int SLOT_Y = LAYOUT.slotOf("y");
    private static final int SLOT_ANCHOR_X = LAYOUT.slotOf("anchor_x");
    private static final int SLOT_ANCHOR_Y = LAYOUT.slotOf("anchor_y");
    private static final int SLOT_SCALE_X = LAYOUT.slotOf("scale_x");
    private static final int SLOT_SCALE_Y = LAYOUT.slotOf("scale_y");
    private static final int SLOT_SOURCE_X = LAYOUT.slotOf("source.x");
    private static final int SLOT_SOURCE_Y = LAYOUT.slotOf("source.y");
    private static final int SLOT_SOURCE_W = LAYOUT.slotOf("source.w");
    private static final int SLOT_SOURCE_H = LAYOUT.slotOf("source.h");
    private static final int SLOT_OPACITY = LAYOUT.slotOf("opacity");
    private static final int SLOT_FONT_SCALE = LAYOUT.slotOf("font_scale");
    private static final int SLOT_COLOR_R = LAYOUT.slotOf("color.r");
    private static final int SLOT_COLOR_G = LAYOUT.slotOf("color.g");
    private static final int SLOT_COLOR_B = LAYOUT.slotOf("color.b");
    private static final int SLOT_MASK_X = LAYOUT.slotOf("mask_x");
    private static final int SLOT_MASK_Y = LAYOUT.slotOf("mask_y");
    private static final int SLOT_MASK_WIDTH = LAYOUT.slotOf("mask_width");
    private static final int SLOT_MASK_HEIGHT = LAYOUT.slotOf("mask_height");
    private static final int SLOT_MASK_RADIUS = LAYOUT.slotOf("mask_radius");
    private static final int SLOT_GRADIENT_ANGLE = LAYOUT.slotOf("gradient_angle");
    private static final int SLOT_GRADIENT_CENTER_X = LAYOUT.slotOf("gradient_center_x");
    private static final int SLOT_GRADIENT_CENTER_Y = LAYOUT.slotOf("gradient_center_y");
    private static final int SLOT_GRADIENT_RADIUS = LAYOUT.slotOf("gradient_radius");

    private final String layerType;
    private final String path;
    private final String text;
    private final int zIndex;
    private final int baseColor;
    private final float baseR;
    private final float baseG;
    private final float baseB;
    private final boolean declaresR;
    private final boolean declaresG;
    private final boolean declaresB;
    private final ColorMaskSpec maskSpec;

    private OverlayClipRuntime(String layerType, String path, String text, int zIndex,
                               int baseColor, boolean declaresR, boolean declaresG, boolean declaresB,
                               ColorMaskSpec maskSpec) {
        this.layerType = layerType;
        this.path = path;
        this.text = text;
        this.zIndex = zIndex;
        this.baseColor = baseColor;
        this.baseR = ((baseColor >> 16) & 0xFF) / 255f;
        this.baseG = ((baseColor >> 8) & 0xFF) / 255f;
        this.baseB = (baseColor & 0xFF) / 255f;
        this.declaresR = declaresR;
        this.declaresG = declaresG;
        this.declaresB = declaresB;
        this.maskSpec = maskSpec;
    }

    /**
     * 解析一个片段的 value-only 字段（每片段一次；调用方按片段身份缓存结果）。
     *
     * @param clip   片段
     * @param values 该片段的 value-only 读取视图（{@link ClipRuntimeCache#values(Clip)}）
     */
    public static OverlayClipRuntime of(Clip clip, ClipValueView values) {
        int baseColor = parseBaseColor(values.s("color", ColorMaskSpec.DEFAULT_COLOR));
        ColorMaskSpec spec = ColorMaskSpec.forObjects(baseColor, values.struct("gradient"),
                values.s("mask_shape", ColorMaskSpec.DEFAULT_MASK_SHAPE),
                values.s("blend_mode", ColorMaskSpec.DEFAULT_BLEND_MODE));
        return new OverlayClipRuntime(
                values.s("layer_type", DEFAULT_LAYER_TYPE),
                values.s("path", ""),
                values.s("text", ""),
                values.i("z_index", CanvasTransform.DEFAULT_Z_INDEX),
                baseColor,
                SLOT_COLOR_R >= 0 && values.declared("color.r"),
                SLOT_COLOR_G >= 0 && values.declared("color.g"),
                SLOT_COLOR_B >= 0 && values.declared("color.b"),
                spec);
    }

    /** 层类型：{@code fade} / {@code image} / {@code subtitle}。 */
    public String layerType() {
        return layerType;
    }

    /** image 素材路径（{@code layer_type=image}）。 */
    public String path() {
        return path;
    }

    /** subtitle 文本（{@code layer_type=subtitle}）。 */
    public String text() {
        return text;
    }

    /** 图层顺序（建层时使用；运行中不重排）。 */
    public int zIndex() {
        return zIndex;
    }

    /** value-only 结构（base color / gradient / mask_shape / blend_mode），不含关键帧颜色缓存。 */
    public ColorMaskSpec maskSpec() {
        return maskSpec;
    }

    /**
     * 在 {@code executionLocalTime} 装配一帧图层参数。
     *
     * @param clip      片段
     * @param localTime 执行局部时间
     * @param cache     该片段的采样缓存
     * @param runtime   本片段的 value-only 解析结果
     * @return 不可变参数对象（播放器立即消费，不长期持有）
     */
    public static OverlayParams compute(Clip clip, double localTime, ClipRuntimeCache cache,
                                        OverlayClipRuntime runtime) {
        SampledClip sample = cache.sample(clip, localTime);
        return switch (runtime.layerType) {
            case "image" -> imageParams(sample, runtime);
            case "subtitle" -> subtitleParams(sample, runtime);
            default -> fadeParams(sample, runtime);
        };
    }

    private static OverlayParams fadeParams(SampledClip sample, OverlayClipRuntime runtime) {
        ColorMaskSpec spec = runtime.maskSpec;
        float opacity = read(sample, SLOT_OPACITY, CanvasTransform.DEFAULT_OPACITY);
        int colorRgb = ColorMaskSpec.rgb(
                readColor(sample, SLOT_COLOR_R, runtime.declaresR, runtime.baseR),
                readColor(sample, SLOT_COLOR_G, runtime.declaresG, runtime.baseG),
                readColor(sample, SLOT_COLOR_B, runtime.declaresB, runtime.baseB));

        float maskX = DEFAULT_MASK_POSITION;
        float maskY = DEFAULT_MASK_POSITION;
        float maskWidth = DEFAULT_MASK_SIZE;
        float maskHeight = DEFAULT_MASK_SIZE;
        float maskRadius = DEFAULT_MASK_RADIUS;
        String maskShape = spec.maskShape();
        if ("rect".equals(maskShape)) {
            maskX = read(sample, SLOT_MASK_X, DEFAULT_MASK_POSITION);
            maskY = read(sample, SLOT_MASK_Y, DEFAULT_MASK_POSITION);
            maskWidth = read(sample, SLOT_MASK_WIDTH, DEFAULT_MASK_SIZE);
            maskHeight = read(sample, SLOT_MASK_HEIGHT, DEFAULT_MASK_SIZE);
        } else if ("circle".equals(maskShape)) {
            maskX = read(sample, SLOT_MASK_X, DEFAULT_MASK_POSITION);
            maskY = read(sample, SLOT_MASK_Y, DEFAULT_MASK_POSITION);
            maskRadius = read(sample, SLOT_MASK_RADIUS, DEFAULT_MASK_RADIUS);
        }

        float gradientAngle = DEFAULT_GRADIENT_ANGLE;
        float gradientCenterX = DEFAULT_GRADIENT_CENTER;
        float gradientCenterY = DEFAULT_GRADIENT_CENTER;
        float gradientRadius = DEFAULT_GRADIENT_RADIUS;
        boolean hasGradient = spec.hasGradient();
        if (hasGradient) {
            if ("radial".equals(spec.gradientType())) {
                gradientCenterX = read(sample, SLOT_GRADIENT_CENTER_X, DEFAULT_GRADIENT_CENTER);
                gradientCenterY = read(sample, SLOT_GRADIENT_CENTER_Y, DEFAULT_GRADIENT_CENTER);
                gradientRadius = read(sample, SLOT_GRADIENT_RADIUS, DEFAULT_GRADIENT_RADIUS);
            } else {
                gradientAngle = read(sample, SLOT_GRADIENT_ANGLE, DEFAULT_GRADIENT_ANGLE);
                gradientCenterX = read(sample, SLOT_GRADIENT_CENTER_X, DEFAULT_GRADIENT_CENTER);
                gradientCenterY = read(sample, SLOT_GRADIENT_CENTER_Y, DEFAULT_GRADIENT_CENTER);
            }
        }

        return new OverlayParams(runtime.layerType,
                CanvasTransform.DEFAULT_POSITION, CanvasTransform.DEFAULT_POSITION,
                CanvasTransform.DEFAULT_ANCHOR, CanvasTransform.DEFAULT_ANCHOR,
                CanvasTransform.DEFAULT_SCALE, CanvasTransform.DEFAULT_SCALE,
                0f, 0f, 1f, 1f,
                opacity, 1f,
                colorRgb,
                maskShape,
                maskX, maskY, maskWidth, maskHeight, maskRadius,
                hasGradient,
                gradientAngle, gradientCenterX, gradientCenterY, gradientRadius,
                runtime.zIndex);
    }

    private static OverlayParams imageParams(SampledClip sample, OverlayClipRuntime runtime) {
        return new OverlayParams(runtime.layerType,
                read(sample, SLOT_X, CanvasTransform.DEFAULT_POSITION),
                read(sample, SLOT_Y, CanvasTransform.DEFAULT_POSITION),
                read(sample, SLOT_ANCHOR_X, CanvasTransform.DEFAULT_ANCHOR),
                read(sample, SLOT_ANCHOR_Y, CanvasTransform.DEFAULT_ANCHOR),
                read(sample, SLOT_SCALE_X, CanvasTransform.DEFAULT_SCALE),
                read(sample, SLOT_SCALE_Y, CanvasTransform.DEFAULT_SCALE),
                read(sample, SLOT_SOURCE_X, 0f),
                read(sample, SLOT_SOURCE_Y, 0f),
                read(sample, SLOT_SOURCE_W, 1f),
                read(sample, SLOT_SOURCE_H, 1f),
                read(sample, SLOT_OPACITY, CanvasTransform.DEFAULT_OPACITY),
                1f,
                runtime.baseColor,
                ColorMaskSpec.DEFAULT_MASK_SHAPE,
                DEFAULT_MASK_POSITION, DEFAULT_MASK_POSITION, DEFAULT_MASK_SIZE, DEFAULT_MASK_SIZE,
                DEFAULT_MASK_RADIUS,
                false,
                DEFAULT_GRADIENT_ANGLE, DEFAULT_GRADIENT_CENTER, DEFAULT_GRADIENT_CENTER,
                DEFAULT_GRADIENT_RADIUS,
                runtime.zIndex);
    }

    private static OverlayParams subtitleParams(SampledClip sample, OverlayClipRuntime runtime) {
        return new OverlayParams(runtime.layerType,
                read(sample, SLOT_X, CanvasTransform.DEFAULT_POSITION),
                read(sample, SLOT_Y, CanvasTransform.DEFAULT_POSITION),
                read(sample, SLOT_ANCHOR_X, CanvasTransform.DEFAULT_ANCHOR),
                read(sample, SLOT_ANCHOR_Y, CanvasTransform.DEFAULT_ANCHOR),
                read(sample, SLOT_SCALE_X, CanvasTransform.DEFAULT_SCALE),
                read(sample, SLOT_SCALE_Y, CanvasTransform.DEFAULT_SCALE),
                0f, 0f, 1f, 1f,
                read(sample, SLOT_OPACITY, CanvasTransform.DEFAULT_OPACITY),
                read(sample, SLOT_FONT_SCALE, 1f),
                runtime.baseColor,
                ColorMaskSpec.DEFAULT_MASK_SHAPE,
                DEFAULT_MASK_POSITION, DEFAULT_MASK_POSITION, DEFAULT_MASK_SIZE, DEFAULT_MASK_SIZE,
                DEFAULT_MASK_RADIUS,
                false,
                DEFAULT_GRADIENT_ANGLE, DEFAULT_GRADIENT_CENTER, DEFAULT_GRADIENT_CENTER,
                DEFAULT_GRADIENT_RADIUS,
                runtime.zIndex);
    }

    /** 采样槽位读取：未声明 / 未采样 → schema 缺省。 */
    private static float read(SampledClip sample, int slot, float fallback) {
        return slot >= 0 && sample.present(slot) ? sample.f(slot) : fallback;
    }

    /** 颜色通道：只有作者显式声明了该通道才用采样值，否则回落基色对应通道。 */
    private static float readColor(SampledClip sample, int slot, boolean declared, float base) {
        return declared && slot >= 0 && sample.present(slot) ? sample.f(slot) : base;
    }

    /** {@code #RRGGBB} → 0xRRGGBB（与 {@link ColorMaskSpec} 同一格式契约）。 */
    private static int parseBaseColor(String text) {
        if (text == null || text.isEmpty()) {
            return 0x000000;
        }
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new ColorMaskSpec.InvalidColorMaskException("color",
                    "颜色格式非法: \"" + text + "\"（需 #RRGGBB 六位十六进制，# 可省）");
        }
        return Integer.parseInt(hex, 16) & 0x00FFFFFF;
    }

    /**
     * 一帧的 OVERLAY 装配结果（不可变）。
     *
     * <p>不适用于当前层类型的字段填 schema 缺省（例如 image 的 {@code fontScale} 恒为 1、fade 的
     * {@code x/y} 恒为 0.5），这样「不读」与「读不到」在参数上可区分。</p>
     */
    public record OverlayParams(
            String layerType,
            float x, float y,
            float anchorX, float anchorY,
            float scaleX, float scaleY,
            float sourceX, float sourceY, float sourceW, float sourceH,
            float opacity, float fontScale,
            int colorRgb,
            String maskShape,
            float maskX, float maskY, float maskWidth, float maskHeight, float maskRadius,
            boolean hasGradient,
            float gradientAngle, float gradientCenterX, float gradientCenterY, float gradientRadius,
            int zIndex) {
    }
}
