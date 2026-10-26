package com.immersivecinematics.immersive_cinematics.camera.transition;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.math.MathModelInstance;

/**
 * 过渡层 — A→B 状态过渡的独立层（进度 / 曲线 / 分参数 / 打断与链式；不进 Base / Modifier 内部）。
 *
 * 输入：A 侧（起点）与 B 侧（目标）的整帧六参数（各自按自身时间采样）+ 声明 + 交界时刻；
 * 输出写进调用方提供的 {@link TransitionFrame}，即覆盖链的脚本导演 Base 写入值（调用方在 Base 写入点
 * 写进链，Modifier 合成在其上）。
 * 窗口与权重：每通道窗口以交界为中心、以该通道时长为宽度；进度钳到 [0,1] 后交该通道曲线
 * （无曲线 = 线性斜坡）；取值 = BLEND 按权重混合 / HOLD 保持 A 侧 / CUT 交界前 A 侧、交界后 B 侧。
 * 混合公式见 {@link TransitionBlend}。
 * 打断：交界变化即打断——{@link TransitionInterrupt#BLEND}（缺省）以当前混合中间态为新 A 侧
 * （逐位连续、不跳变）、时长重新起算；{@link TransitionInterrupt#SNAP} 立即完成
 * （= 时长 0 特例，本过渡窗口内输出恒取 B 侧）。
 * A→B→C 链式 = 打断 blend 的自然延伸；同一 / 更早的交界结算后不再重开。
 * 分配契约：{@link #blendInto} 逐帧零分配（不 new / 不装箱 / 不建集合）；曲线实例只在过渡开始时分配一次。
 * 线程契约：仅客户端主线程；每条过渡流一份实例，状态不跨流共享。
 */
public final class CameraTransitionLayer {

    /** 通道下标：位置（三轴同一模式）。 */
    private static final int C_POSITION = Integer.numberOfTrailingZeros(CameraChannels.POSITION);

    /** 通道下标：yaw（度）。 */
    private static final int C_YAW = Integer.numberOfTrailingZeros(CameraChannels.YAW);

    /** 通道下标：pitch（度）。 */
    private static final int C_PITCH = Integer.numberOfTrailingZeros(CameraChannels.PITCH);

    /** 通道下标：roll（度）。 */
    private static final int C_ROLL = Integer.numberOfTrailingZeros(CameraChannels.ROLL);

    /** 通道下标：fov（度）。 */
    private static final int C_FOV = Integer.numberOfTrailingZeros(CameraChannels.FOV);

    /** 通道下标：zoom（倍率）。 */
    private static final int C_ZOOM = Integer.numberOfTrailingZeros(CameraChannels.ZOOM);

    /** A 侧留底：打断 blend 后冻结为打断前的混合中间态（正常过渡每帧由调用方提供 A 侧）。 */
    private final TransitionFrame frozenFrom = new TransitionFrame();

    /** 最近一次过渡输出（= 打断 blend 的「当前混合中间态」；跨帧复用）。 */
    private final TransitionFrame lastOutput = new TransitionFrame();

    /** 曲线输入 / 输出缓冲（长度 1：过渡曲线为 SCALAR 域；跨帧复用）。 */
    private final double[] curveIn = new double[1];
    private final double[] curveOut = new double[1];

    /** 当前过渡的声明；{@code null} = 无过渡。 */
    private TransitionSpec spec;

    /** 当前过渡的交界时刻（秒）。 */
    private float boundary;

    /** 当前过渡的起点（秒）：新建 = 交界 − 最大时长/2；打断 blend = 打断当帧。 */
    private float startTime;

    /** 已结算的交界（秒）：同一 / 更早的交界不再重开过渡。 */
    private float settledBoundary = Float.NEGATIVE_INFINITY;

    /** 新建过渡 = {@code true}（各通道窗口以交界为中心）；打断续接 = {@code false}（从打断当帧起算）。 */
    private boolean centered = true;

    /** A 侧是否冻结（打断 blend 后为 {@code true}）。 */
    private boolean fromFrozen;

    /** 本过渡是否被 snap 打断（{@code true} = 已立即完成：输出恒取 B 侧）。 */
    private boolean snapped;

    /** 上一步时刻（秒；曲线 dt 用）。 */
    private float lastNow;

    /** 曲线实例（过渡开始时按声明实例化；{@code null} 元素 = 恒等）。 */
    private MathModelInstance[] curves;

    /**
     * 每帧过渡求值：推进本层状态并把输出写进 {@code out}。
     *
     * @param now      当前时刻（秒，单调；与交界同一时间基准）
     * @param boundary 交界时刻（秒；= 两侧片段的交界）
     * @param spec     过渡声明；{@code null} / 最大时长为 0 = 无过渡
     * @param from     A 侧状态（起点；本层在打断 blend 后改用自己的留底）
     * @param to       B 侧状态（目标）
     * @param out      输出（本层写入；调用方复用）
     * @return {@code true} = 本层产出过渡输出；{@code false} = 无过渡（{@code out} 已直通 B 侧）
     */
    public boolean blendInto(float now, float boundary, TransitionSpec spec, TransitionFrame from,
                             TransitionFrame to, TransitionFrame out) {
        if (now < lastNow) {
            // 时间轴非单调（宏观循环折返 / seek）= 状态自愈：清空过渡与结算记录，按新交界重新开始
            reset();
        }
        if (spec == null || spec.maxDuration() <= 0f) {
            if (isActive()) {
                settle();
            }
            out.copyFrom(to);
            return false;
        }
        if (isActive() && boundary != this.boundary) {
            interrupt(now, boundary, spec);
        } else if (!isActive() && boundary > settledBoundary) {
            begin(now, boundary, spec);
        }
        if (!isActive()) {
            out.copyFrom(to);
            return false;
        }
        if (now >= startTime + this.spec.maxDuration()) {
            settle();
            out.copyFrom(to);
            return false;
        }
        advance(now, from, to, out);
        return true;
    }

    /** 本层当前是否在过渡中（未走完 / 未结算）。 */
    public boolean isActive() {
        return spec != null;
    }

    /** 归位（停播 / seek / 脚本替换）：清空过渡状态；下一次求值按新交界重新开始。 */
    public void reset() {
        settle();
        settledBoundary = Float.NEGATIVE_INFINITY;
        boundary = 0f;
        startTime = 0f;
        centered = true;
        lastNow = 0f;
    }

    /** 新建过渡：窗口以交界为中心（各通道按自身时长对齐交界），A 侧每帧由调用方提供。 */
    private void begin(float now, float boundary, TransitionSpec spec) {
        this.spec = spec;
        this.boundary = boundary;
        this.startTime = spec.windowStart(boundary);
        this.centered = true;
        this.fromFrozen = false;
        this.snapped = false;
        this.curves = spec.instantiateCurves();
        this.lastNow = now;
    }

    /** 打断处置：snap = 本窗口内恒取 B 侧；blend = 以 {@link #lastOutput} 为新 A 侧、从当帧起算。 */
    private void interrupt(float now, float boundary, TransitionSpec spec) {
        if (spec.interrupt() == TransitionInterrupt.SNAP) {
            this.spec = spec;
            this.boundary = boundary;
            this.startTime = spec.windowStart(boundary);
            this.centered = true;
            this.snapped = true;
            this.fromFrozen = false;
            this.curves = null;
            this.lastNow = now;
            return;
        }
        frozenFrom.copyFrom(lastOutput);
        fromFrozen = true;
        this.snapped = false;
        this.spec = spec;
        this.boundary = boundary;
        this.startTime = now;
        this.centered = false;
        this.curves = spec.instantiateCurves();
        this.lastNow = now;
    }

    /** 结算当前过渡：同一交界不再重开（更早的交界同样忽略）。 */
    private void settle() {
        settledBoundary = boundary;
        spec = null;
        curves = null;
        fromFrozen = false;
        snapped = false;
    }

    /** 逐通道求值：进度 → 曲线权重 → 取值；A 侧取留底（打断 blend 后）或调用方当帧值。 */
    private void advance(float now, TransitionFrame from, TransitionFrame to, TransitionFrame out) {
        float dt = now - lastNow;
        if (!(dt > 0f)) {
            dt = 0f;
        }
        lastNow = now;
        TransitionFrame a = fromFrozen ? frozenFrom : from;
        out.px = value(C_POSITION, now, dt, a.px, to.px);
        out.py = value(C_POSITION, now, dt, a.py, to.py);
        out.pz = value(C_POSITION, now, dt, a.pz, to.pz);
        out.yaw = (float) value(C_YAW, now, dt, a.yaw, to.yaw);
        out.pitch = (float) value(C_PITCH, now, dt, a.pitch, to.pitch);
        out.roll = (float) value(C_ROLL, now, dt, a.roll, to.roll);
        out.fov = (float) value(C_FOV, now, dt, a.fov, to.fov);
        out.zoom = (float) value(C_ZOOM, now, dt, a.zoom, to.zoom);
        lastOutput.copyFrom(out);
    }

    /** 单通道取值：snap 打断 = 恒取 B 侧；BLEND 按权重混合（公式按通道数值域分派）；HOLD = A 侧；CUT = 交界前 A 侧、交界后 B 侧。 */
    private double value(int channel, float now, float dt, double a, double b) {
        if (snapped) {
            return b;
        }
        TransitionMode mode = spec.mode(channel);
        if (mode == TransitionMode.HOLD) {
            return a;
        }
        if (mode == TransitionMode.CUT) {
            return now >= boundary ? b : a;
        }
        float weight = weight(channel, now, dt);
        if (channel == C_POSITION) {
            return TransitionBlend.axis(a, b, weight);
        }
        if (channel == C_YAW || channel == C_ROLL) {
            return TransitionBlend.angle((float) a, (float) b, weight);
        }
        if (channel == C_ZOOM) {
            return TransitionBlend.zoom((float) a, (float) b, weight);
        }
        return TransitionBlend.linear((float) a, (float) b, weight);
    }

    /** 该通道本帧权重：进度钳到 [0,1] 后交该通道曲线；无曲线 = 恒等（线性斜坡）。 */
    private float weight(int channel, float now, float dt) {
        float progress = clamp01(progress(channel, now));
        MathModelInstance curve = curves != null ? curves[channel] : null;
        if (curve == null) {
            return progress;
        }
        curveIn[0] = progress;
        curve.step(now, dt, curveIn, curveOut);
        return (float) curveOut[0];
    }

    /** 该通道进度 = (now − 窗口起点) / 时长；新建过渡的窗口以交界为中心，打断续接从打断当帧起算。 */
    private float progress(int channel, float now) {
        float duration = spec.duration(channel);
        float start = startTime;
        if (centered) {
            start += (spec.maxDuration() - duration) / 2f;
        }
        if (!(duration > 0f)) {
            // 零时长通道（片段级缺省 ≤ 0 且本通道未声明时长）= 本通道不做过渡：交界前取 A 侧、之后取 B 侧
            return now >= start ? 1f : 0f;
        }
        return (now - start) / duration;
    }

    /** 钳到 [0,1]；NaN 原样通过（进度由本层保证有限：时长已过零守卫，时刻来自调用方时钟）。 */
    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }
}
