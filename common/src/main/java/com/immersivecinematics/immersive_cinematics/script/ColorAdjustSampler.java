package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyType;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.script.schema.ObjectDef;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调色采样器 — 在片段本地时间处把一段 clip 的调色字段求值成一份 {@link ColorAdjustParams}。
 *
 * 两条数据源：旧格式（{@code clip.hasObjects()==false}）走 {@link #sample(Clip, double)}，按旧关键帧
 * 逐字段插值；新格式走 {@link #sampleObjects}/{@link #sampleCameraLaneObjects}，通道从
 * {@link SampledClip} 槽位读、LUT / 混合从 value-only 对象读。两条路径的通道顺序、缺省与
 * 曲线强度语义一致，渲染侧不区分来源。
 *
 * 旧路径纯关键帧求值、无状态；新路径的十条曲线在解析期已归一为 {@link ColorCurve} 并挂在
 * {@link Clip} 上，这里只按 clip 缓存 LUT 来源（{@link CurveCache}），运行期不再解析曲线结构。两个使用方：
 * ADJUST 轨（master，作用于整体画面）与 {@link ScriptPlayer#collectCameraLanes()} 的逐 lane 采样
 * （每条 lane 用自己的相机片段与 {@link CameraLane#clipLocalTime()}）。
 * 结果只承载 RGB：参数语义 / 范围 / 缺省见 {@link ColorAdjustParams}，像素级契约见 {@code ic_color_adjust.fsh}。
 * LUT 与层级混合只属 ADJUST 轨（master）：lane 路径恒得 {@code lut = null} 与缺省混合。
 */
public final class ColorAdjustSampler {

    /**
     * ADJUST 的 59 个通道名（= {@code TrackSchemas.ADJUST_CHANNELS} 声明顺序 = {@link ColorAdjustParams}
     * 的标量分量顺序）：槽位只在类加载时按名字解析一次，帧内直接按 {@link #ADJUST_SLOTS} 取数组下标。
     */
    private static final String[] ADJUST_CHANNEL_NAMES = {
            "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
            "hue", "saturation", "vibrance", "lightness",
            "temperature", "tint",
            "red", "green", "blue",
            "mix_rr", "mix_rg", "mix_rb", "mix_gr", "mix_gg", "mix_gb", "mix_br", "mix_bg", "mix_bb",
            "curve_strength", "r_curve_strength", "g_curve_strength", "b_curve_strength",
            "lift_r", "lift_g", "lift_b", "gamma_r", "gamma_g", "gamma_b", "gain_r", "gain_g", "gain_b",
            "lut_strength",
            "hv_h_strength", "hv_s_strength", "hv_l_strength", "lv_s_strength", "sv_s_strength", "sv_l_strength",
            "hue_red", "sat_red", "hue_yellow", "sat_yellow", "hue_green", "sat_green",
            "hue_cyan", "sat_cyan", "hue_blue", "sat_blue", "hue_magenta", "sat_magenta",
            "grayscale", "invert", "blend_amount"};

    /** CAMERA lane 的 57 个通道名（= ADJUST 去掉 {@code lut_strength} / {@code blend_amount}）。 */
    private static final String[] CAMERA_CHANNEL_NAMES = {
            "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
            "hue", "saturation", "vibrance", "lightness",
            "temperature", "tint",
            "red", "green", "blue",
            "mix_rr", "mix_rg", "mix_rb", "mix_gr", "mix_gg", "mix_gb", "mix_br", "mix_bg", "mix_bb",
            "curve_strength", "r_curve_strength", "g_curve_strength", "b_curve_strength",
            "lift_r", "lift_g", "lift_b", "gamma_r", "gamma_g", "gamma_b", "gain_r", "gain_g", "gain_b",
            "hv_h_strength", "hv_s_strength", "hv_l_strength", "lv_s_strength", "sv_s_strength", "sv_l_strength",
            "hue_red", "sat_red", "hue_yellow", "sat_yellow", "hue_green", "sat_green",
            "hue_cyan", "sat_cyan", "hue_blue", "sat_blue", "hue_magenta", "sat_magenta",
            "grayscale", "invert"};

    private static final int[] ADJUST_SLOTS = resolveSlots(TrackType.ADJUST, ADJUST_CHANNEL_NAMES);
    private static final int[] CAMERA_SLOTS = resolveSlots(TrackType.CAMERA, CAMERA_CHANNEL_NAMES);
    private static final float[] ADJUST_DEFAULTS = resolveDefaults(TrackType.ADJUST, ADJUST_SLOTS);
    private static final float[] CAMERA_DEFAULTS = resolveDefaults(TrackType.CAMERA, CAMERA_SLOTS);

    private ColorAdjustSampler() {
    }

    // ========== 新格式（对象模型）采样 ==========

    /**
     * ADJUST 轨道的新格式采样：59 个通道从 {@link SampledClip} 按槽位读取，LUT / 混合模式从
     * value-only 对象读取；十条曲线直接取 {@link Clip} 上解析期归一的 {@link ColorCurve}
     * （按 clip 缓存在 {@link CurveCache}），LUT 走 {@link CubeLutLoader}（按路径缓存）。
     *
     * <p>通道顺序 = {@code ADJUST_CHANNELS} 声明顺序 = {@link ColorAdjustParams} 的标量分量顺序。
     * 未声明的通道回落 schema 缺省（强度类 = 1）；没有 LUT 时 {@code lutStrength} 取 0（整步跳过）。
     * 曲线强度即使没有对应曲线也按对象值上报（渲染侧在 LUT 缺席时自行归零）。</p>
     *
     * @param clip   片段（clipId / 轨道类型 / 已归一曲线）
     * @param sample 该片段在当前 {@code executionLocalTime} 的采样
     * @param values 该片段的 value-only 读取视图（LUT 路径、输入 gamma、混合模式）
     * @param curves 曲线缓存（脚本替换时由调用方 {@link CurveCache#clear()}）
     */
    public static ColorAdjustParams sampleObjects(Clip clip, SampledClip sample, ClipValueView values,
                                                  CurveCache curves) {
        ParsedCurves parsed = curves.get(clip);
        float[] rgbLut = lut(parsed.rgb);
        float[] rLut = lut(parsed.r);
        float[] gLut = lut(parsed.g);
        float[] bLut = lut(parsed.b);
        float[] hvHLut = lut(parsed.hvH);
        float[] hvSLut = lut(parsed.hvS);
        float[] hvLLut = lut(parsed.hvL);
        float[] lvSLut = lut(parsed.lvS);
        float[] svSLut = lut(parsed.svS);
        float[] svLLut = lut(parsed.svL);
        String lutPath = values.s("lut", null);
        CubeLut lut = lutPath == null ? null : CubeLutLoader.forClip(clip.getClipId(), lutPath);

        int[] s = ADJUST_SLOTS;
        float[] d = ADJUST_DEFAULTS;
        return new ColorAdjustParams(
                read(sample, s, d, 0), read(sample, s, d, 1), read(sample, s, d, 2),
                read(sample, s, d, 3), read(sample, s, d, 4), read(sample, s, d, 5),
                read(sample, s, d, 6), read(sample, s, d, 7), read(sample, s, d, 8), read(sample, s, d, 9),
                read(sample, s, d, 10), read(sample, s, d, 11),
                read(sample, s, d, 12), read(sample, s, d, 13), read(sample, s, d, 14),
                read(sample, s, d, 15), read(sample, s, d, 16), read(sample, s, d, 17),
                read(sample, s, d, 18), read(sample, s, d, 19),
                read(sample, s, d, 20), read(sample, s, d, 21), read(sample, s, d, 22), read(sample, s, d, 23),
                rgbLut, rLut, gLut, bLut,
                read(sample, s, d, 24), read(sample, s, d, 25),
                read(sample, s, d, 26), read(sample, s, d, 27),
                read(sample, s, d, 28), read(sample, s, d, 29), read(sample, s, d, 30),
                read(sample, s, d, 31), read(sample, s, d, 32), read(sample, s, d, 33),
                read(sample, s, d, 34), read(sample, s, d, 35), read(sample, s, d, 36),
                lut,
                lut == null ? 0.0F : read(sample, s, d, 37),
                values.f("lut_input_gamma", 1.0F),
                hvHLut, hvSLut, hvLLut, lvSLut, svSLut, svLLut,
                read(sample, s, d, 38), read(sample, s, d, 39), read(sample, s, d, 40),
                read(sample, s, d, 41), read(sample, s, d, 42), read(sample, s, d, 43),
                read(sample, s, d, 44), read(sample, s, d, 45), read(sample, s, d, 46), read(sample, s, d, 47),
                read(sample, s, d, 48), read(sample, s, d, 49), read(sample, s, d, 50), read(sample, s, d, 51),
                read(sample, s, d, 52), read(sample, s, d, 53), read(sample, s, d, 54), read(sample, s, d, 55),
                read(sample, s, d, 56), read(sample, s, d, 57),
                ColorAdjustParams.blendModeCode(values.s("blend_mode", "normal")),
                read(sample, s, d, 58));
    }

    /**
     * CAMERA lane 的新格式采样：57 个通道从该 lane 片段的 {@link SampledClip} 读取，十条曲线直接取
     * {@link Clip} 上解析期归一的 {@link ColorCurve}（CAMERA 对象表决定声明，可能缺席）。lane 路径没有
     * LUT / 层级混合：{@code lut=null}、{@code lutStrength=0}、{@code lutInputGamma=1}、
     * {@code blendMode=normal}、{@code blendAmount=1}。
     *
     * @param clip   产出该 lane 的相机片段（已归一曲线）
     * @param sample 该片段在 {@code lane.clipLocalTime()} 的采样
     * @param curves 曲线缓存（与 ADJUST 分开持有，脚本替换时清空）
     */
    public static ColorAdjustParams sampleCameraLaneObjects(Clip clip, SampledClip sample, CurveCache curves) {
        ParsedCurves parsed = curves.get(clip);
        float[] rgbLut = lut(parsed.rgb);
        float[] rLut = lut(parsed.r);
        float[] gLut = lut(parsed.g);
        float[] bLut = lut(parsed.b);
        float[] hvHLut = lut(parsed.hvH);
        float[] hvSLut = lut(parsed.hvS);
        float[] hvLLut = lut(parsed.hvL);
        float[] lvSLut = lut(parsed.lvS);
        float[] svSLut = lut(parsed.svS);
        float[] svLLut = lut(parsed.svL);

        int[] s = CAMERA_SLOTS;
        float[] d = CAMERA_DEFAULTS;
        return new ColorAdjustParams(
                read(sample, s, d, 0), read(sample, s, d, 1), read(sample, s, d, 2),
                read(sample, s, d, 3), read(sample, s, d, 4), read(sample, s, d, 5),
                read(sample, s, d, 6), read(sample, s, d, 7), read(sample, s, d, 8), read(sample, s, d, 9),
                read(sample, s, d, 10), read(sample, s, d, 11),
                read(sample, s, d, 12), read(sample, s, d, 13), read(sample, s, d, 14),
                read(sample, s, d, 15), read(sample, s, d, 16), read(sample, s, d, 17),
                read(sample, s, d, 18), read(sample, s, d, 19),
                read(sample, s, d, 20), read(sample, s, d, 21), read(sample, s, d, 22), read(sample, s, d, 23),
                rgbLut, rLut, gLut, bLut,
                read(sample, s, d, 24), read(sample, s, d, 25),
                read(sample, s, d, 26), read(sample, s, d, 27),
                read(sample, s, d, 28), read(sample, s, d, 29), read(sample, s, d, 30),
                read(sample, s, d, 31), read(sample, s, d, 32), read(sample, s, d, 33),
                read(sample, s, d, 34), read(sample, s, d, 35), read(sample, s, d, 36),
                null, 0.0F, 1.0F,
                hvHLut, hvSLut, hvLLut, lvSLut, svSLut, svLLut,
                read(sample, s, d, 37), read(sample, s, d, 38), read(sample, s, d, 39),
                read(sample, s, d, 40), read(sample, s, d, 41), read(sample, s, d, 42),
                read(sample, s, d, 43), read(sample, s, d, 44), read(sample, s, d, 45), read(sample, s, d, 46),
                read(sample, s, d, 47), read(sample, s, d, 48), read(sample, s, d, 49), read(sample, s, d, 50),
                read(sample, s, d, 51), read(sample, s, d, 52), read(sample, s, d, 53), read(sample, s, d, 54),
                read(sample, s, d, 55), read(sample, s, d, 56),
                ColorAdjustParams.BLEND_NORMAL, 1.0F);
    }

    /**
     * 按 clip 缓存曲线组：曲线由解析期归一（{@code ScriptParser} 写出 {@link ColorCurve}），这里只把
     * {@link Clip} 上的十条曲线引用收成一组，供 {@link #lut(ColorCurve)} 复用，不再做任何结构解析。
     * 非线程安全（客户端主线程，与 {@code ClipRuntimeCache} 同一约束）。
     */
    public static final class CurveCache {

        private final Map<Clip, ParsedCurves> cache = new IdentityHashMap<>();

        /** 脚本替换 / 停播：丢弃全部片段的曲线组。 */
        public void clear() {
            cache.clear();
        }

        /** 当前缓存的片段数（探针用）。 */
        public int size() {
            return cache.size();
        }

        private ParsedCurves get(Clip clip) {
            ParsedCurves parsed = cache.get(clip);
            if (parsed == null) {
                parsed = ParsedCurves.of(clip);
                cache.put(clip, parsed);
            }
            return parsed;
        }
    }

    /** 一个片段的十条曲线（解析期归一的 {@link ColorCurve} 引用；{@code null} = 没有该曲线）。 */
    private static final class ParsedCurves {

        private final ColorCurve rgb;
        private final ColorCurve r;
        private final ColorCurve g;
        private final ColorCurve b;
        private final ColorCurve hvH;
        private final ColorCurve hvS;
        private final ColorCurve hvL;
        private final ColorCurve lvS;
        private final ColorCurve svS;
        private final ColorCurve svL;

        private ParsedCurves(ColorCurve rgb, ColorCurve r, ColorCurve g, ColorCurve b,
                             ColorCurve hvH, ColorCurve hvS, ColorCurve hvL,
                             ColorCurve lvS, ColorCurve svS, ColorCurve svL) {
            this.rgb = rgb;
            this.r = r;
            this.g = g;
            this.b = b;
            this.hvH = hvH;
            this.hvS = hvS;
            this.hvL = hvL;
            this.lvS = lvS;
            this.svS = svS;
            this.svL = svL;
        }

        private static ParsedCurves of(Clip clip) {
            return new ParsedCurves(
                    clip.getColorCurve(), clip.getRCurve(), clip.getGCurve(), clip.getBCurve(),
                    clip.getHvHCurve(), clip.getHvSCurve(), clip.getHvLCurve(),
                    clip.getLvSCurve(), clip.getSvSCurve(), clip.getSvLCurve());
        }
    }

    /**
     * 单通道读取：作者声明过（{@code presence=true}）用采样值，否则回落 schema 缺省
     * （{@code ClipSampler} 只写声明过的槽位；未声明槽位的数值数组内容无意义）。
     */
    private static float read(SampledClip sample, int[] slots, float[] defaults, int index) {
        int slot = slots[index];
        return sample.present(slot) ? sample.f(slot) : defaults[index];
    }

    /** 按槽位解析 schema 缺省值（通道缺省只有 0 / 1 两种，仍按声明读取，避免硬编码）。 */
    private static float[] resolveDefaults(TrackType type, int[] slots) {
        List<ObjectDef> defs = ObjectSlotLayout.of(type).defs();
        float[] defaults = new float[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Object value = defs.get(slots[i]).defaultValue();
            defaults[i] = value instanceof Number number ? number.floatValue() : 0.0F;
        }
        return defaults;
    }

    /** 按名字把通道名解析成槽位；类加载时执行一次，缺声明 = 编程错误（fail fast）。 */
    private static int[] resolveSlots(TrackType type, String[] names) {
        ObjectSlotLayout layout = ObjectSlotLayout.of(type);
        int[] slots = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            int slot = layout.slotOf(names[i]);
            if (slot < 0 || layout.typeOf(slot) != PropertyType.FLOAT) {
                throw new IllegalStateException(type + " 缺少调色通道对象: " + names[i]);
            }
            slots[i] = slot;
        }
        return slots;
    }

    /**
     * 本片段在片段本地时间处的全部取值（顺序 = {@link ColorAdjustParams} 分量顺序 = shader 操作栈顺序）。
     *
     * 标量通道缺省 0 = 无效果；曲线 LUT 按 clip 缓存，此处只插值各曲线的强度（缺省 1，无曲线 → 0）。
     */
    public static ColorAdjustParams sample(Clip clip, double localTime) {
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
    private static float strength(List<Keyframe> keyframes, double localTime, String key, float[] lut) {
        return lut == null ? 0.0F : KeyframeInterpolator.interpolateChannel(keyframes, localTime, key, 1.0F);
    }

    /** 单通道取值：缺省 0 = 无效果（关键帧没写该字段就是不做这项调整）。 */
    private static float channel(List<Keyframe> keyframes, double localTime, String key) {
        return KeyframeInterpolator.interpolateChannel(keyframes, localTime, key, 0.0F);
    }
}
