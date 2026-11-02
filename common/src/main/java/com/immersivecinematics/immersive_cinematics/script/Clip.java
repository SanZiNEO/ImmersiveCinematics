package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionInterrupt;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import com.immersivecinematics.immersive_cinematics.math.path.PathSpec;
import com.immersivecinematics.immersive_cinematics.script.object.ClipObjects;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyMode;
import com.immersivecinematics.immersive_cinematics.script.object.SampledProperty;
import com.immersivecinematics.immersive_cinematics.script.execution.LoopMode;
import com.immersivecinematics.immersive_cinematics.util.MathUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通用片段容器：所有轨道共享 {@code startTime} / {@code duration}；
 * 轨道特有字段存在 {@code data} map 中（由 {@link SchemaLoader} 定义），关键帧使用 {@link Keyframe} 泛型。
 *
 * <p>新格式片段把片段级字段写成 {@code objects} 里的采样属性。为了让旧消费方（过渡 / 运动模型 / 呼吸 /
 * modifier / 曲线 / LUT / dimension / 音频）继续按字段名读取，{@link #getString(String, String)}、
 * {@link #getFloat(String, float)}、{@link #getInt(String, int)}、{@link #getBool(String, boolean)}、
 * {@link #getObject(String)} 在 {@code data} 没有该键时回落 {@code objects} 里的同名属性——
 * <b>只回落 {@link PropertyMode#VALUE}（value-only）属性</b>；{@code animated} / {@code event} 属性必须走
 * 采样器（{@code ClipSampler} / {@code SampledClip}），这里一律按「未提供」处理。旧格式片段的
 * {@code objects} 为空，回落路径恒不命中，行为与之前逐位一致。</p>
 */
public class Clip {

    /** 片段起始秒：float 存储（脚本 JSON 精度）；运行时读取后按 double 参与计算，写回时显式窄化。 */
    private final float startTime;
    /** 片段时长秒：float 存储（脚本 JSON 精度）；运行时读取后按 double 参与计算，写回时显式窄化。 */
    private final float duration;
    private final TrackType trackType;
    private final Map<String, Object> data;
    private final List<Keyframe> keyframes;
    /** 新对象模型的采样属性集合；旧格式片段为 {@link ClipObjects#EMPTY}。 */
    private final ClipObjects objects;

    /**
     * 是否新格式（播放控制格式，I-1 {@code objects} 片段）：执行循环按格式区分——
     * 新格式 AUDIO 的 {@code loop} 是执行循环；旧格式 AUDIO 的 {@code loop} 仍是文件循环。
     */
    private final boolean playControlFormat;

    /** 过渡声明缓存（{@link #getTransitionSpec()} 首次访问建成；片段数据解析后只读，故可缓存）。 */
    private TransitionSpec transitionSpec;

    /** fade 颜色遮罩定义缓存（{@link #getColorMaskSpec()} 首次访问建成；片段数据解析后只读，故可缓存）。 */
    private ColorMaskSpec colorMaskSpec;
    /** 是否已尝试建立 {@link #colorMaskSpec}（非 OVERLAY 轨的 {@code null} 结果同样只解析一次）。 */
    private boolean colorMaskSpecResolved;

    /** 旧格式构造器：不携带新对象模型（{@link ClipObjects#EMPTY}），执行循环按旧格式口径。 */
    public Clip(float startTime, float duration, TrackType trackType,
                Map<String, Object> data, List<Keyframe> keyframes) {
        this(startTime, duration, trackType, data, keyframes, ClipObjects.EMPTY, false);
    }

    /** 旧格式构造器（带对象模型）：执行循环仍按旧格式口径。 */
    public Clip(float startTime, float duration, TrackType trackType,
                Map<String, Object> data, List<Keyframe> keyframes, ClipObjects objects) {
        this(startTime, duration, trackType, data, keyframes, objects, false);
    }

    /**
     * @param playControlFormat 是否新格式（播放控制格式）：{@code true} = {@code objects} 片段
     *                          （{@code ScriptParser.parseObjectClip}），AUDIO 的 {@code loop} 按执行循环解释
     */
    public Clip(float startTime, float duration, TrackType trackType,
                Map<String, Object> data, List<Keyframe> keyframes, ClipObjects objects,
                boolean playControlFormat) {
        this.startTime = startTime;
        this.duration = duration;
        this.trackType = trackType;
        this.data = data != null ? data : Collections.emptyMap();
        this.keyframes = keyframes != null ? keyframes : Collections.emptyList();
        this.objects = objects != null ? objects : ClipObjects.EMPTY;
        this.playControlFormat = playControlFormat;
    }

    // ── 通用字段 ──

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public TrackType getTrackType() { return trackType; }
    public Map<String, Object> getData() { return data; }
    public List<Keyframe> getKeyframes() { return keyframes; }

    /** 新对象模型的采样属性集合（旧格式片段为空集合）。 */
    public ClipObjects getObjects() { return objects; }

    /** 是否携带新对象模型；空 {@code objects} 与旧格式片段同为 {@code false}。 */
    public boolean hasObjects() { return !objects.isEmpty(); }

    /** 是否为无限时长片段（负数即视为无限时长） */
    public boolean isInfinite() { return duration < 0f; }

    // ── 泛型字段访问 ──

    /**
     * 该键的 value-only 回落属性：{@code data} 没有该键时，若 {@code objects} 里显式声明了同名属性且形态为
     * {@link PropertyMode#VALUE}，返回该属性；否则 {@code null}。
     *
     * <p>返回属性而非值，是为了区分「没声明」与「声明为 {@code null}」。{@code animated} / {@code event}
     * 属性不参与回落：动画量必须走采样器。</p>
     */
    private SampledProperty valueOnly(String key) {
        SampledProperty property = objects.get(key);
        return property != null && property.mode() == PropertyMode.VALUE ? property : null;
    }

    /** 读字符串：{@code data} 优先；否则取同名 value-only 属性的 {@link String} 值；否则缺省。 */
    public String getString(String key, String defaultValue) {
        Object v = data.get(key);
        if (v instanceof String) return (String) v;
        SampledProperty property = valueOnly(key);
        Object fallback = property != null ? property.value() : null;
        return fallback instanceof String ? (String) fallback : defaultValue;
    }

    /**
     * 读浮点：{@code data} 优先；否则取同名 value-only 属性的 {@link Number} 值（同样过
     * {@link MathUtil#sanitizeFloatLogged}）；否则缺省。
     */
    public float getFloat(String key, float defaultValue) {
        Object v = data.get(key);
        if (v instanceof Number) {
            // 数据入口守卫：非有限值（NaN / ±Infinity）回落缺省——片段级字段（lut_input_gamma / volume /
            // pitch / fade_* 等）同样不得直达采样器 / shader（clamp(NaN) 在主流驱动上取 0 = 整屏变黑）
            return MathUtil.sanitizeFloatLogged(((Number) v).floatValue(), defaultValue, "clip." + key);
        }
        SampledProperty property = valueOnly(key);
        Object fallback = property != null ? property.value() : null;
        return fallback instanceof Number
                ? MathUtil.sanitizeFloatLogged(((Number) fallback).floatValue(), defaultValue, "clip." + key)
                : defaultValue;
    }

    /** 读整数：{@code data} 优先；否则取同名 value-only 属性的 {@link Number} 值；否则缺省。 */
    public int getInt(String key, int defaultValue) {
        Object v = data.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        SampledProperty property = valueOnly(key);
        Object fallback = property != null ? property.value() : null;
        return fallback instanceof Number ? ((Number) fallback).intValue() : defaultValue;
    }

    /** 读布尔：{@code data} 优先；否则取同名 value-only 属性的 {@link Boolean} 值；否则缺省。 */
    public boolean getBool(String key, boolean defaultValue) {
        Object v = data.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        SampledProperty property = valueOnly(key);
        Object fallback = property != null ? property.value() : null;
        return fallback instanceof Boolean ? (Boolean) fallback : defaultValue;
    }

    /**
     * 读任意对象：{@code data} 命中且非 {@code null} 优先；否则若同名 value-only 属性存在，返回其静态值
     * （可能为 {@code null}）；否则 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        Object v = data.get(key);
        if (v != null) return (T) v;
        SampledProperty property = valueOnly(key);
        return property != null ? (T) property.value() : null;
    }

    // ── Convenience: CAMERA ──

    public boolean isMorph() {
        return "morph".equals(getString("transition", "cut"));
    }

    public float getTransitionDuration() {
        return getFloat("transition_duration", 0.5f);
    }

    /**
     * 本片段的过渡声明（分参数 / 曲线 / 打断）——{@link #isMorph()} 为 {@code true} 时恒非 {@code null}。
     *
     * 值 = 片段级字段归一而成（{@code transition_duration} / {@code transition_curve} /
     * {@code transition_params} / {@code transition_interrupt}）；未声明任何过渡字段时为 {@code null}。
     * 首次访问建成并缓存（片段数据在解析后只读）。未声明的通道 / 键按片段级缺省补齐
     * （全六通道 BLEND、同一时长、无曲线 = 线性斜坡、BLEND 打断）。
     */
    public TransitionSpec getTransitionSpec() {
        if (transitionSpec == null && declaresTransition()) {
            transitionSpec = TransitionSpec.resolve(getObject("transition_params"), getTransitionDuration(),
                    getObject("transition_curve"),
                    TransitionInterrupt.byName(getString("transition_interrupt", "blend")));
        }
        return transitionSpec;
    }

    /** 是否声明了任何过渡字段（{@link #getTransitionSpec()} 的建成判据）；{@code objects} 的 value-only 声明同样算。 */
    private boolean declaresTransition() {
        return declares("transition") || declares("transition_duration")
                || declares("transition_params") || declares("transition_curve")
                || declares("transition_interrupt");
    }

    /** {@code data} 显式写了该键，或 {@code objects} 里声明了同名 value-only 属性。 */
    private boolean declares(String key) {
        return data.containsKey(key) || valueOnly(key) != null;
    }

    /**
     * 本片段的 fade 颜色遮罩定义；只有 {@link TrackType#OVERLAY} 片段有，其余轨道恒 {@code null}。
     * 首次访问由 {@link ColorMaskSpec#from(Clip)} 建成并缓存（片段数据解析后只读，{@code null} 也只判定一次）。
     * 结构非法时抛 {@link ColorMaskSpec.InvalidColorMaskException}，由 {@code ScriptParser} 在解析期
     * 转成带完整字段路径的 {@code ScriptParseException}。
     */
    public ColorMaskSpec getColorMaskSpec() {
        if (!colorMaskSpecResolved) {
            colorMaskSpecResolved = true;
            colorMaskSpec = trackType == TrackType.OVERLAY ? ColorMaskSpec.from(this) : null;
        }
        return colorMaskSpec;
    }

    public boolean isPositionModeRelative() {
        return "relative".equals(getString("position_mode", "relative"));
    }

    /**
     * 本片段渲染时所用的维度（{@code dimension}，如 {@code minecraft:overworld}）。
     * 空串 = 玩家当前维度（缺省）：客户端只把该 lane 的画面按此维度渲染，服务端不传送玩家。
     */
    public String getDimension() {
        return getString("dimension", "");
    }

    public boolean isPlayControlFormat() {
        return playControlFormat;
    }

    /**
     * 是否为执行循环（按次数重复执行）：{@code loop=true} 且（新格式 或 非 AUDIO 轨）。
     * 旧格式 AUDIO 的 {@code loop} 是文件循环，不算执行循环。
     */
    public boolean isExecutionLoop() {
        return getBool("loop", false) && (playControlFormat || trackType != TrackType.AUDIO);
    }

    /** 旧调用名：执行循环（旧格式 AUDIO 文件循环不算）。 */
    public boolean isLoop() {
        return isExecutionLoop();
    }

    /** 是否为旧格式 AUDIO 的文件循环（{@code loop=true} 且非播放控制格式）。 */
    public boolean isFileLoop() {
        return trackType == TrackType.AUDIO && !playControlFormat && getBool("loop", false);
    }

    public int getLoopCount() {
        return getInt("loop_count", -1);
    }

    /**
     * 循环时间映射模式：repeat=周期内从头到尾重复；pingpong=往复折返。
     * 仅 loop=true 时生效。
     */
    public String getLoopMode() {
        return getString("loop_mode", "repeat");
    }

    /** 归一后的循环模式（运行期不再比较字符串）。 */
    public LoopMode getLoopModeValue() {
        return LoopMode.fromName(getLoopMode(), LoopMode.REPEAT);
    }

    /**
     * 是否为无限循环（loop=true 且 loop_count=-1）。
     * 注：loop_count=0 在解析期已被修正为 1（有限循环），不会到达这里。
     */
    public boolean isLoopInfinite() {
        return isExecutionLoop() && getLoopCount() < 0;
    }

    /**
     * 片段是否"永不结束"的统一判断：
     * duration 为负（无限时长片段）或无限执行循环（loop=true + loop_count=-1）。
     * 所有"这个片段会不会结束"的判定都应走此方法。
     */
    public boolean isEffectivelyInfinite() {
        return isInfinite() || isLoopInfinite();
    }

    /** 片段是否有有限展开窗口末端（{@link #getWindowEnd()} 的唯一前置判据）。 */
    public boolean hasFiniteWindowEnd() {
        return !isEffectivelyInfinite();
    }

    /**
     * 片段活跃窗口末端（不含）：
     * 有限 = {@code start + duration × (loop ? max(1, loopCount) : 1)}；
     * 无限（时长或循环）没有有限末端，调用抛 {@link IllegalStateException}（编程错误，不用数值哨兵）。
     */
    public double getWindowEnd() {
        if (isEffectivelyInfinite()) {
            throw new IllegalStateException("无限执行没有有限窗口末端：" + this);
        }
        if (isExecutionLoop()) {
            return startTime + (double) duration * Math.max(1, getLoopCount());
        }
        return startTime + duration;
    }

    /**
     * 本片段的路径定义（clip 级 {@code curve}，解析期由
     * {@link com.immersivecinematics.immersive_cinematics.script.schema.PathCurveRegistry} 按 {@code type} 建成）。
     * {@code null} = 未声明曲线（逐段线性）。
     */
    public PathSpec getPathSpec() {
        return getObject("curve");
    }

    /**
     * 片段级静态 Modifier 声明（clip 级 {@code modifier} 对象，解析期归一为
     * {@link ClipModifier}）；{@code null} = 本片段不声明（缺省，链上零改动）。
     *
     * 一个片段至多一条声明；声明随本片段对象存活，注册 / 注销由播放实例驱动
     * （{@code PlaybackInstance}）。
     */
    public ClipModifier getModifier() {
        return getObject("modifier");
    }

    /**
     * 本片段活跃时是否跳过 Modifier 链（clip 级 {@code suppress_modifiers}；缺省 {@code false}）。
     *
     * 读侧 = 脚本导演 Base 层（{@code ScriptDirectorBase#suppressesModifiers()}）：写入方把本片段的值
     * 随六参数一起写进全局写入缓冲，胜出时链整条跳过 Modifier。
     */
    public boolean getSuppressModifiers() {
        return getBool("suppress_modifiers", false);
    }

    /**
     * 片段级判定规则（clip 级 {@code rules} 数组，解析期归一为 {@link ClipRule} 列表）。
     *
     * 顺序 = 声明顺序（阻塞规则按声明逐条生效）；本片段不写 {@code rules} 时为空表（恒非 {@code null}）。
     * 规则随本片段对象存活、播放期只读；条件求值归判定源、动作执行归动作集，见 {@link ClipRule}。
     */
    @SuppressWarnings("unchecked")
    public List<ClipRule> getRules() {
        Object v = data.get("rules");
        return v instanceof List ? (List<ClipRule>) v : List.of();
    }

    // ── Convenience: ADJUST ──

    /**
     * RGB 复合曲线（曲线组形态 b：曲线定义一次、关键帧只控 {@code curve_strength}）。
     * {@code null} = 本片段无曲线（字段缺省），曲线值见 {@link ColorCurve}。
     */
    public ColorCurve getColorCurve() {
        return getObject("rgb_curve");
    }

    /**
     * R 每通道曲线（曲线组形态 b，与 {@link #getColorCurve()} 同构；关键帧控 {@code r_curve_strength}）。
     * {@code null} = 本片段无该曲线。
     */
    public ColorCurve getRCurve() {
        return getObject("r_curve");
    }

    /** G 每通道曲线（同 {@link #getRCurve()}，关键帧控 {@code g_curve_strength}）。 */
    public ColorCurve getGCurve() {
        return getObject("g_curve");
    }

    /** B 每通道曲线（同 {@link #getRCurve()}，关键帧控 {@code b_curve_strength}）。 */
    public ColorCurve getBCurve() {
        return getObject("b_curve");
    }

    /**
     * clip 级 LUT 文件名（{@code resource/} 目录下的 {@code .cube}；{@code null} = 本片段不做 LUT）。
     * 只有 ADJUST 轨（整体画面 / master）带 LUT：按轨道类型挡一道，lane 路径恒 {@code null}
     * （{@code ScriptValidator} 同样拦下），保证逐 lane 的相机片段永远不会被套 LUT。
     * 只存文件名、不解析：解析结果由 {@link CubeLutLoader#forClip}
     * 按路径缓存、按 clip 记引用（键 = {@link #getClipId()}），同一文件跨片段共享同一不可变实例。
     */
    public String getLut() {
        return trackType == TrackType.ADJUST ? getString("lut", null) : null;
    }

    /**
     * clip 级 LUT 输入域适配幂指数（缺省 {@code 1.0} = 不变换）：查表前对输入 RGB 逐通道
     * {@code v = pow(clamp(c, 0, 1), gamma)}，以 {@code v} 为四面体查表坐标。
     * 与 {@link #getLut()} 同款只对 ADJUST 轨生效（lane 路径恒得 1.0）；无 LUT 时该值被忽略（查表整步不生效）。
     */
    public float getLutInputGamma() {
        return trackType == TrackType.ADJUST ? getFloat("lut_input_gamma", 1.0F) : 1.0F;
    }

    /**
     * clip 级混合模式（{@code normal} 缺省 / {@code multiply} / {@code screen} / {@code soft_light} /
     * {@code overlay}）：调整层输出 {@code c_adj} 与基画面 {@code c_base} 按模式整体混合，
     * {@code c = mix(c_base, blend(c_base, c_adj, mode), amount)}，强度 = 关键帧 {@code blend_amount}。
     * 与 {@link #getLut()} 同款只对 ADJUST 轨生效：lane 路径恒得 {@code "normal"}（混合步骤恒等）。
     */
    public String getBlendMode() {
        return trackType == TrackType.ADJUST ? getString("blend_mode", "normal") : "normal";
    }

    /**
     * 本片段的稳定标识（{@link CubeLutLoader#forClip} 的 clip 键）：片段对象解析期一次建成、播放期存活，
     * 故用对象身份；不同实例理论上可能撞值，但该表只记录「哪些片段引用了 LUT」，
     * 撞值不影响取到的实例（LUT 实例本身按文件路径共享）。
     */
    public String getClipId() {
        return "clip@" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * HvH 曲线（六条 hue 曲线，DaVinci 曲线页口径）：以 hue 为键、输出 hue；关键帧控 {@code hv_h_strength}。
     * {@code null} = 本片段无该曲线。
     */
    public ColorCurve getHvHCurve() {
        return getObject("hv_h_curve");
    }

    /** HvS 曲线：以 hue 为键、输出饱和度（关键帧控 {@code hv_s_strength}）。 */
    public ColorCurve getHvSCurve() {
        return getObject("hv_s_curve");
    }

    /** HvL 曲线：以 hue 为键、输出亮度（关键帧控 {@code hv_l_strength}）。 */
    public ColorCurve getHvLCurve() {
        return getObject("hv_l_curve");
    }

    /** LvS 曲线：以亮度为键、输出饱和度（关键帧控 {@code lv_s_strength}）。 */
    public ColorCurve getLvSCurve() {
        return getObject("lv_s_curve");
    }

    /** SvS 曲线：以饱和度为键、输出饱和度（关键帧控 {@code sv_s_strength}）。 */
    public ColorCurve getSvSCurve() {
        return getObject("sv_s_curve");
    }

    /** SvL 曲线：以饱和度为键、输出亮度（关键帧控 {@code sv_l_strength}）。 */
    public ColorCurve getSvLCurve() {
        return getObject("sv_l_curve");
    }

    // ── Convenience: AUDIO ──

    public String getSound() {
        return getString("sound", "");
    }

    public float getVolume() {
        return getFloat("volume", 1.0f);
    }

    public float getAudioPitch() {
        return getFloat("pitch", 1.0f);
    }

    public float getFadeIn() {
        return getFloat("fade_in", 0f);
    }

    public float getFadeOut() {
        return getFloat("fade_out", 0f);
    }

    public String getSource() {
        return getString("source", "file");
    }

    public String getAttenuation() {
        return getString("attenuation", "linear");
    }

    public String getAudioPositionMode() {
        return getString("position_mode", "relative");
    }


    // ── Convenience: EVENT ──

    public String getEventType() {
        return getString("event_type", "");
    }

    public String getCommand() {
        return getString("command", "");
    }

    /** 是否为瞬发事件（duration=0） */
    public boolean isInstant() { return duration == 0f; }

    @Override
    public String toString() {
        return String.format("Clip{type=%s, start=%.2f, dur=%.2f, data=%s, kfs=%d}",
                trackType, startTime, duration, data, keyframes.size());
    }
}
