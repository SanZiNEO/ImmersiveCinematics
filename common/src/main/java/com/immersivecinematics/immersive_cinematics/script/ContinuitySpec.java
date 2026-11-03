package com.immersivecinematics.immersive_cinematics.script;

/**
 * 触发器级连续语义开关（JSON {@code meta.triggers[].continuity}）。
 *
 * <p>全部字段可省；缺省 = {@link #DEFAULT}，即「关闭连续语义」——此时引擎走旧的
 * 点判定路径，行为逐位不变。
 *
 * <p><b>{@code sweep}</b>：把相邻两次采样之间的运动近似成线段 / 最短弧，
 * 判定这段运动是否碰到条件区域。只对 {@code location} / {@code structure} / {@code facing}
 * 有几何语义；其余类型忽略（校验层给 warning）。
 *
 * <p><b>{@code dwell}</b>：停留门槛（tick）。要求玩家连续处于区域内至少该时长才触发；
 * 只对轮询类触发器生效，事件类忽略（校验层给 warning）。
 *
 * <p><b>{@code window}</b>：组合器内事件类子条件的时间窗口（tick）。{@code window > 0} 时
 * {@code all_of} / {@code any} 里的事件类子条件按「最近 W tick 内是否发生过」求值；
 * 只对 {@code all_of} / {@code any} 生效，其余类型忽略（校验层给 warning）。
 *
 * <p>纯数据载体，无 MC 依赖。
 *
 * @param sweep       是否启用几何扫掠（段判定）
 * @param dwellTicks  停留门槛（tick，{@code 0..}{@link #MAX_TICKS}）；0 = 无门槛
 * @param windowTicks 事件类子条件的窗口（tick，{@code 0..}{@link #MAX_TICKS}）；0 = 不启用
 */
public record ContinuitySpec(boolean sweep, int dwellTicks, int windowTicks) {

    /** 未声明 {@code continuity}：关闭扫掠、无停留门槛、无窗口。 */
    public static final ContinuitySpec DEFAULT = new ContinuitySpec(false, 0, 0);

    /** 连续语义相关 tick 值的上限（{@code dwell} / {@code window} 同口径）。 */
    public static final int MAX_TICKS = 1200;

    /** 是否等价于 {@link #DEFAULT}（引擎据此走旧快速路径）。 */
    public boolean isDefault() {
        return !sweep && dwellTicks == 0 && windowTicks == 0;
    }
}
