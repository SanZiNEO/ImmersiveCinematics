package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionInterrupt;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionParams;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitPolicy;
import com.immersivecinematics.immersive_cinematics.script.execution.LoopMode;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipIdentity;
import com.immersivecinematics.immersive_cinematics.script.object.ClipObjects;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.spi.MathModel;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

/**
 * 通用片段容器：所有轨道共享 {@code startTime} / {@code duration} 与循环三键
 * （{@code loop} / {@code loop_count} / {@code loop_mode}）；轨道特有字段与动画量都走
 * {@code objects} 采样属性。
 *
 * <p>片段级字段（过渡 / 运动模型 / 呼吸 / modifier / 曲线 / LUT / dimension / 音频）由
 * {@link ClipValueView} 读取 value-only 属性；动画量必须走采样器（{@code ClipSampler} /
 * {@code SampledClip}）。本类不提供通用字段读取口。</p>
 */
public class Clip {

    /** 片段起始秒：float 存储（脚本 JSON 精度）；运行时读取后按 double 参与计算，写回时显式窄化。 */
    private final float startTime;
    /** 片段时长秒：float 存储（脚本 JSON 精度）；运行时读取后按 double 参与计算，写回时显式窄化。 */
    private final float duration;
    private final TrackTypeId trackType;
    /** 是否按次数重复执行（{@code loop}）。 */
    private final boolean loop;
    /** 执行次数（{@code loop_count}；{@code -1} = 无限循环；解析期 0 已修正为 1）。 */
    private final int loopCount;
    /** 循环时间映射模式（{@code loop_mode}；未声明归一为 {@link LoopMode#REPEAT}）。 */
    private final LoopMode loopMode;
    /** 片段声明的对象属性集合（采样属性 / value-only 字段 / 事件）。 */
    private final ClipObjects objects;

    /**
     * 脚本内稳定 id（{@code null} = 未命名）：解析期从 JSON {@code id} 字段读入并按
     * {@link ClipIdentity#PATTERN} 校验；规则 / 路径 / 分支 / 出口都按此引用片段。
     */
    private final String clipId;

    /**
     * 片段默认出口策略（{@code exit}，{@code null} = 未声明）。
     *
     * <p>解析期两段式：{@link ScriptParser} 先把原始 {@code exit} JSON 存进 {@link #rawExit}（此时脚本
     * 还不存在，GOTO 目标无法解析），脚本装配完成后由解析器统一用
     * {@code ClipExitPolicyParser} 解析并 {@link #setExitPolicy 写回}。运行期只读；出口策略只在
     * <b>分支入口</b>启动的执行完成时被 {@code ClipBranchController} 解析。</p>
     */
    private ClipExitPolicy exitPolicy;

    /** 解析期的原始 {@code exit} 对象（{@code null} = 未声明；脚本装配完成后保持原样供诊断）。 */
    private JsonObject rawExit;

    /** 过渡声明缓存（{@link #getTransitionSpec()} 首次访问建成；片段数据解析后只读，故可缓存）。 */
    private TransitionSpec transitionSpec;

    /**
     * 唯一构造器。
     *
     * @param loop       是否按次数重复执行（{@code loop}）
     * @param loopCount  执行次数（{@code loop_count}；{@code -1} = 无限循环）
     * @param loopMode   循环时间映射模式（{@code null} → {@link LoopMode#REPEAT}）
     * @param objects    片段的对象属性集合（{@code null} → {@link ClipObjects#EMPTY}）
     * @param clipId     脚本内 id（{@code null} = 未命名片段）；非 {@code null} 时调用方须已按
     *                   {@link ClipIdentity#PATTERN} 校验（解析器负责），这里不重复校验以免把
     *                   程序化构造路径的分支埋进异常
     * @param exitPolicy 片段默认出口策略（{@code null} = 未声明）；解析期由
     *                   {@code ClipExitPolicyParser} 在脚本装配后填充，程序化构造路径可直接传
     */
    public Clip(float startTime, float duration, TrackTypeId trackType,
                boolean loop, int loopCount, LoopMode loopMode,
                ClipObjects objects, String clipId, ClipExitPolicy exitPolicy) {
        this.startTime = startTime;
        this.duration = duration;
        this.trackType = trackType;
        this.loop = loop;
        this.loopCount = loopCount;
        this.loopMode = loopMode != null ? loopMode : LoopMode.REPEAT;
        this.objects = objects != null ? objects : ClipObjects.EMPTY;
        this.clipId = clipId;
        this.exitPolicy = exitPolicy;
    }

    // ── 通用字段 ──

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public TrackTypeId getTrackType() { return trackType; }

    /** 片段声明的对象属性集合（只读）。 */
    public ClipObjects getObjects() { return objects; }

    /** 是否声明了对象属性（{@code objects} 非空）。 */
    public boolean hasObjectProperties() { return !objects.isEmpty(); }

    /** 是否为无限时长片段（负数即视为无限时长） */
    public boolean isInfinite() { return duration < 0f; }

    // ── Convenience: CAMERA ──

    public boolean isMorph() {
        return "morph".equals(new ClipValueView(this).s("transition", "cut"));
    }

    public float getTransitionDuration() {
        return new ClipValueView(this).f("transition_duration", 0.5f);
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
            ClipValueView values = new ClipValueView(this);
            transitionSpec = TransitionSpec.resolve((TransitionParams) values.o("transition_params"),
                    getTransitionDuration(),
                    (MathModel) values.o("transition_curve"),
                    TransitionInterrupt.byName(values.s("transition_interrupt", "blend")));
        }
        return transitionSpec;
    }

    /** 是否声明了任何过渡字段（{@link #getTransitionSpec()} 的建成判据）。 */
    private boolean declaresTransition() {
        ClipValueView values = new ClipValueView(this);
        return values.declared("transition") || values.declared("transition_duration")
                || values.declared("transition_params") || values.declared("transition_curve")
                || values.declared("transition_interrupt");
    }

    public boolean isPositionModeRelative() {
        return "relative".equals(new ClipValueView(this).s("position_mode", "relative"));
    }

    /**
     * 本片段渲染时所用的维度（{@code dimension}，如 {@code minecraft:overworld}）。
     * 空串 = 玩家当前维度（缺省）：客户端只把该 lane 的画面按此维度渲染，服务端不传送玩家。
     */
    public String getDimension() {
        return new ClipValueView(this).s("dimension", "");
    }

    /** 是否为执行循环（按次数重复执行）：{@code loop=true}。 */
    public boolean isExecutionLoop() {
        return loop;
    }

    public int getLoopCount() {
        return loopCount;
    }

    /**
     * 循环时间映射模式：repeat=周期内从头到尾重复；pingpong=往复折返。
     * 仅 loop=true 时生效。
     */
    public String getLoopMode() {
        return loopMode.id();
    }

    /** 归一后的循环模式（运行期不再比较字符串）。 */
    public LoopMode getLoopModeValue() {
        return loopMode;
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
     * 片段级静态 Modifier 声明（clip 级 {@code modifier} 对象，解析期归一为
     * {@link ClipModifier}）；{@code null} = 本片段不声明（缺省，链上零改动）。
     *
     * 一个片段至多一条声明；声明随本片段对象存活，注册 / 注销由播放实例驱动
     * （{@code PlaybackInstance}）。
     */
    public ClipModifier getModifier() {
        return (ClipModifier) new ClipValueView(this).o("modifier");
    }

    /**
     * 本片段活跃时是否跳过 Modifier 链（clip 级 {@code suppress_modifiers}；缺省 {@code false}）。
     *
     * 读侧 = 脚本导演 Base 层（{@code ScriptDirectorBase#suppressesModifiers()}）：写入方把本片段的值
     * 随六参数一起写进全局写入缓冲，胜出时链整条跳过 Modifier。
     */
    public boolean getSuppressModifiers() {
        return new ClipValueView(this).b("suppress_modifiers", false);
    }

    // ── Convenience: ADJUST ──

    /**
     * RGB 复合曲线（曲线组形态 b：曲线定义一次、关键帧只控 {@code curve_strength}）。
     * {@code null} = 本片段无曲线（字段缺省），曲线值见 {@link ColorCurve}。
     */
    public ColorCurve getColorCurve() {
        return (ColorCurve) new ClipValueView(this).o("rgb_curve");
    }

    /**
     * R 每通道曲线（曲线组形态 b，与 {@link #getColorCurve()} 同构；关键帧控 {@code r_curve_strength}）。
     * {@code null} = 本片段无该曲线。
     */
    public ColorCurve getRCurve() {
        return (ColorCurve) new ClipValueView(this).o("r_curve");
    }

    /** G 每通道曲线（同 {@link #getRCurve()}，关键帧控 {@code g_curve_strength}）。 */
    public ColorCurve getGCurve() {
        return (ColorCurve) new ClipValueView(this).o("g_curve");
    }

    /** B 每通道曲线（同 {@link #getRCurve()}，关键帧控 {@code b_curve_strength}）。 */
    public ColorCurve getBCurve() {
        return (ColorCurve) new ClipValueView(this).o("b_curve");
    }

    /**
     * clip 级 LUT 文件名（{@code resource/} 目录下的 {@code .cube}；{@code null} = 本片段不做 LUT）。
     * 只有 ADJUST 轨（整体画面 / master）带 LUT：按轨道类型挡一道，lane 路径恒 {@code null}
     * （{@code ScriptValidator} 同样拦下），保证逐 lane 的相机片段永远不会被套 LUT。
     * 只存文件名、不解析：解析结果由 {@link CubeLutLoader#forClip}
     * 按路径缓存、按 clip 记引用（键 = {@link #getClipId()}），同一文件跨片段共享同一不可变实例。
     */
    public String getLut() {
        return TrackTypeId.ADJUST.equals(trackType) ? new ClipValueView(this).s("lut", null) : null;
    }

    /**
     * 本片段的键：脚本内稳定 id（{@link #scriptClipId()}）优先；未命名片段回落到对象身份键
     * {@code "clip@" + identityHashCode}——即既有行为，{@link CubeLutLoader#forClip} 的引用表
     * 与 {@code ExecutionDiagnostics} 的显示名都按此记账。返回值恒非 {@code null}。
     */
    public String getClipId() {
        return clipId != null ? clipId : "clip@" + Integer.toHexString(System.identityHashCode(this));
    }

    /** 脚本内稳定 id（{@code null} = 未命名片段）；与 {@link #getClipId()} 的区别是不做对象键回落。 */
    public String scriptClipId() {
        return clipId;
    }

    /** 片段身份（未命名片段 → {@code null}）。 */
    public ClipIdentity identity() {
        return clipId != null ? ClipIdentity.of(clipId) : null;
    }

    // ── 出口策略（片段默认） ──

    /**
     * 片段默认出口策略（{@code exit}）；{@code null} = 未声明（运行期按 {@code CONTINUE}）。
     *
     * <p>只在<b>分支入口</b>启动的执行完成时生效；主线放置的片段完成时不读出策略。</p>
     */
    public ClipExitPolicy getExitPolicy() {
        return exitPolicy;
    }

    /** 本片段是否声明了默认出口策略。 */
    public boolean hasExitPolicy() {
        return exitPolicy != null;
    }

    /**
     * 解析期写回出口策略（{@code ScriptParser} 在脚本装配完成后统一解析，仅调用一次）。
     * 运行期只读；重复调用是编程错误。
     *
     * @throws IllegalStateException 已有出口策略（两次写回）
     */
    void setExitPolicy(ClipExitPolicy exitPolicy) {
        if (this.exitPolicy != null) {
            throw new IllegalStateException("片段出口策略已写回，不能重复设置：" + this);
        }
        this.exitPolicy = exitPolicy;
    }

    /** 原始 {@code exit} 对象（解析期暂存；{@code null} = 未声明）。 */
    public JsonObject getRawExit() {
        return rawExit;
    }

    /**
     * 暂存原始 {@code exit} 对象（解析期由 {@code ScriptParser} 在脚本对象建好之前调用；
     * GOTO 目标此时无法解析，故延后到脚本装配完成后统一解析）。
     */
    void setRawExit(JsonObject rawExit) {
        if (this.rawExit != null) {
            throw new IllegalStateException("片段 exit 已暂存，不能重复设置：" + this);
        }
        this.rawExit = rawExit;
    }

    /**
     * HvH 曲线（六条 hue 曲线，DaVinci 曲线页口径）：以 hue 为键、输出 hue；关键帧控 {@code hv_h_strength}。
     * {@code null} = 本片段无该曲线。
     */
    public ColorCurve getHvHCurve() {
        return (ColorCurve) new ClipValueView(this).o("hv_h_curve");
    }

    /** HvS 曲线：以 hue 为键、输出饱和度（关键帧控 {@code hv_s_strength}）。 */
    public ColorCurve getHvSCurve() {
        return (ColorCurve) new ClipValueView(this).o("hv_s_curve");
    }

    /** HvL 曲线：以 hue 为键、输出亮度（关键帧控 {@code hv_l_strength}）。 */
    public ColorCurve getHvLCurve() {
        return (ColorCurve) new ClipValueView(this).o("hv_l_curve");
    }

    /** LvS 曲线：以亮度为键、输出饱和度（关键帧控 {@code lv_s_strength}）。 */
    public ColorCurve getLvSCurve() {
        return (ColorCurve) new ClipValueView(this).o("lv_s_curve");
    }

    /** SvS 曲线：以饱和度为键、输出饱和度（关键帧控 {@code sv_s_strength}）。 */
    public ColorCurve getSvSCurve() {
        return (ColorCurve) new ClipValueView(this).o("sv_s_curve");
    }

    /** SvL 曲线：以饱和度为键、输出亮度（关键帧控 {@code sv_l_strength}）。 */
    public ColorCurve getSvLCurve() {
        return (ColorCurve) new ClipValueView(this).o("sv_l_curve");
    }

    @Override
    public String toString() {
        return String.format("Clip{type=%s, start=%.2f, dur=%.2f, loop=%s, loopCount=%d, loopMode=%s, objects=%s}",
                trackType, startTime, duration, loop, loopCount, loopMode, objects);
    }
}
