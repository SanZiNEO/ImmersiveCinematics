package com.immersivecinematics.immersive_cinematics.control;

/**
 * 按键/鼠标事件的传输目标，由 {@link InputRouter} 按当前会话为每个事件选定。
 *
 * 新增目标只需加枚举值，路由分支在 Mixin 里补；枚举自身不携带状态。
 */
public enum InputTarget {
    /** 放行，原样交给游戏正常处理 */
    GAME,
    /** 拦截，但更新我们自己关注的 KeyMapping 状态（如跳过键） */
    SELF,
    /** 完全拦截，不传给任何人 */
    BLOCK,
    /** 飞行取景模式：输入交给 FlightController，不传给游戏/编辑器 UI */
    FLIGHT
}
