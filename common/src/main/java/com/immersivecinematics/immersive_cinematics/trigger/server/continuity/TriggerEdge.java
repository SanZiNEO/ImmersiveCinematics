package com.immersivecinematics.immersive_cinematics.trigger.server.continuity;

/**
 * 单个（玩家, 脚本, 触发器）的区域边沿状态机：进入 / 离开 / 停留，纯状态、零 MC 依赖。
 *
 * <p>职责是把「本轮是否在区域内」这一布尔序列，折算成三个语义事件：
 * <ul>
 *   <li>{@code entered}：从「不在区域内」跨到「在区域内」的那一次采样；</li>
 *   <li>{@code left}：从「在离开缓冲内」跨到「连缓冲都出了」的那一次采样；</li>
 *   <li>{@code fire}：本轮是否应当触发（含 {@code dwell} 停留门槛与 {@code on_enter} 单次边沿）。</li>
 * </ul>
 *
 * <p><b>离开缓冲</b>：{@code inExitRegion} 是外扩后的区域（{@code exit_buffer}）。
 * 只要还在缓冲内，{@code inside / armed / trueSince} 都保持不变——这保证玩家在边界附近抖动时
 * 不会反复「离开 → 重新进入」；只有彻底出了缓冲才复位。
 *
 * <p><b>停留（{@code dwell}）</b>：{@code trueSince} 记录「本轮连续为真的起点 tick」。
 * 注意它<b>以采样点真值为准</b>——只在采样时更新，不知道两次采样之间玩家是否短暂离开过；
 * 因此 {@code dwell} 是<b>保守近似</b>：真实停留时长可能略短于判定结果。
 * 采样不连续（{@code continuous == false}，传送 / 维度切换 / 载具变化 / 位移超阈值）时
 * 重新起算，避免把跳变前后的两段停留拼成一段。
 *
 * <p><b>单次边沿（{@code on_enter}）</b>：{@code armed} 在「进入可触发状态」时为真，
 * 触发一次后置假，直到彻底离开缓冲才重新置真。非 {@code on_enter} 触发器不看 {@code armed}。
 *
 * <p>线程契约：只在服务端主线程使用（无同步）。
 */
public final class TriggerEdge {

    /**
     * 一次采样的边沿判定结果。
     *
     * @param fire    本轮是否应当触发（已含 {@code dwell} 与 {@code on_enter} 门槛）
     * @param entered 本轮是否发生「进入」（上一轮不在区域内 → 本轮在）
     * @param left    本轮是否发生「离开」（上一轮在区域内 → 本轮连离开缓冲都出了）
     */
    public record Decision(boolean fire, boolean entered, boolean left) {}

    /** 上一轮是否在原始区域内（离开缓冲内、原始区域外时保持为真）。 */
    private boolean inside;
    /** {@code on_enter} 触发资格：进入后为真，触发一次后为假，彻底离开后复位为真。 */
    private boolean armed = true;
    /** 本轮连续为真的起始 tick；-1 = 当前不在区域内 / 尚未起算。 */
    private int trueSince = -1;

    /** 上一采样点；null = 尚未采样（不连续）。 */
    private MotionSample lastSample;
    /** 上一采样点所属的代次；与当前代次不等 = 期间发生过跳变。 */
    private int generation;

    /**
     * 观察一次采样，推进状态机并给出本轮决策。
     *
     * @param inOriginal   本轮是否在原始触发区域内（点判定或段判定命中）
     * @param inExitRegion 本轮是否仍在离开缓冲区域内（{@code exit_buffer} 外扩后；无外扩时等于 {@code inOriginal}）
     * @param continuous   本轮与上一采样是否属于同一连续段（同一代次且已有上一采样）
     * @param tick         本轮采样的服务端 tick
     * @param dwellTicks   停留门槛（tick）；{@code <= 0} = 无门槛
     * @param onEnter      是否只在进入瞬间触发一次
     */
    public Decision observe(boolean inOriginal, boolean inExitRegion, boolean continuous,
                            int tick, int dwellTicks, boolean onEnter) {
        boolean entered = false;
        boolean left = false;

        if (!inExitRegion) {
            // 彻底离开（连外扩缓冲都出了）：记录离开，复位进入态与触发资格。
            left = inside;
            inside = false;
            armed = true;
            trueSince = -1;
        } else if (inOriginal) {
            // 在原始区域内：进入边沿 + 停留起算（不连续则重新起算）。
            entered = !inside;
            inside = true;
            if (trueSince < 0 || !continuous) {
                trueSince = tick;
            }
        }
        // 否则：在离开缓冲内、不在原始区域——保持 inside / armed / trueSince 不变。

        boolean dwellOk = dwellTicks <= 0 || (inOriginal && tick - trueSince >= dwellTicks);
        boolean fire = inOriginal && dwellOk && (!onEnter || armed);
        if (fire) {
            armed = false;
        }
        return new Decision(fire, entered, left);
    }

    /** 上一采样点；null = 尚未采样。 */
    public MotionSample lastSample() {
        return lastSample;
    }

    /** 上一采样点所属代次（{@link PlayerMotion} 的跳变计数）。 */
    public int generation() {
        return generation;
    }

    /** 是否已有上一采样点（决定本轮能否扫掠）。 */
    public boolean hasSample() {
        return lastSample != null;
    }

    /** 记录本轮采样点与代次；下一次 {@link #observe} 即可用 {@link #lastSample()} 做段判定。 */
    public void setSample(MotionSample sample, int generation) {
        this.lastSample = sample;
        this.generation = generation;
    }

    /** 清空全部状态（区域进入态、触发资格、停留起算、采样点、代次）。 */
    public void reset() {
        inside = false;
        armed = true;
        trueSince = -1;
        lastSample = null;
        generation = 0;
    }
}
