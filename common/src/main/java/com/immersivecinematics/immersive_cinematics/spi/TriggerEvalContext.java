package com.immersivecinematics.immersive_cinematics.trigger.server.continuity;

/**
 * 一次触发器求值的连续语义上下文：本轮的采样区间 + 时间窗口。
 *
 * <p><b>{@code sweep}</b>：本触发器是否声明了几何扫掠（{@code continuity.sweep}），
 * 且本轮确实拥有可靠的上一采样点。两者都成立时 {@link #canSweep()} 才为真——
 * 求值器据此决定「点判定未命中后是否再跑段判定」。
 *
 * <p><b>{@code from} / {@code to}</b>：相邻两次采样快照。{@code from == null} 表示
 * 不连续（首次采样 / 传送 / 维度切换 / 载具变化 / 位移超阈值），此时只能做点判定。
 * {@code to} 是调用方刚刚捕获的当前采样，恒非空（{@link #disabled(int)} 除外）。
 *
 * <p><b>{@code windowTicks}</b>：事件类子条件的时间窗口（tick），来自触发器声明的
 * {@code continuity.window}；0 = 未启用（事件类子条件一律不满足）。
 *
 * <p>纯数据载体，无 MC 依赖，可无头直调。
 *
 * @param tick        求值所属服务端 tick
 * @param sweep       本触发器是否启用几何扫掠（{@code continuity.sweep}）
 * @param from        上一采样点；null = 无连续段可用
 * @param to          当前采样点；{@link #disabled(int)} 时为 null
 * @param windowTicks 事件类子条件的窗口（tick）；0 = 未启用
 */
public record TriggerEvalContext(int tick, boolean sweep, MotionSample from, MotionSample to, int windowTicks) {

    /** 关闭连续语义的上下文：只走点判定（未声明 {@code continuity} 的旧路径）。 */
    public static TriggerEvalContext disabled(int tick) {
        return new TriggerEvalContext(tick, false, null, null, 0);
    }

    /** 是否可以跑段判定：声明了扫掠且两次采样都在手。 */
    public boolean canSweep() {
        return sweep && from != null && to != null;
    }
}
