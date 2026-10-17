package com.immersivecinematics.immersive_cinematics.client.lane;

/**
 * lane 剔除状态的换入 / 换出驱动口（由 {@code LaneCullStateMixin} 实现在 {@code LevelRenderer} 上）。
 *
 * 换入 / 换出必须成对：{@link #ic$pushLaneCullState} 把当前字段值存进帧栈保存位、换入该 lane 的槽；
 * {@link #ic$popLaneCullState} 反向。{@link #ic$popAllLaneCullStates} 是异常兜底（lane pass 中途抛出时
 * 保证所有层弹净、字段回到原版值）。
 *
 * 实现可能缺位（mixin 未应用 / 第三方 {@code @Overwrite} 导致注入跳过）：调用方按 {@code instanceof}
 * 判定，缺位时只是不换 per-lane 剔除字段，lane 仍然照常渲染。
 */
public interface LaneCullBridge {

    /**
     * 换入一条 lane 的剔除状态。
     *
     * @param slot 该 lane 的槽（由 {@link LaneCullSlots#acquire()} 取得，跨帧持有）；未播种时以当前字段值播种
     */
    void ic$pushLaneCullState(LaneCullSlot slot);

    /** 换出栈顶一层（把本 pass 的改动存回 lane 槽、恢复上一层保存值）；未配对时无副作用。 */
    void ic$popLaneCullState();

    /** 弹净全部未换出的层（异常兜底，可重复调用）。 */
    void ic$popAllLaneCullStates();
}
