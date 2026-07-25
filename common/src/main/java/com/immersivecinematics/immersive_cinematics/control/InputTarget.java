package com.immersivecinematics.immersive_cinematics.control;

/**
 * 按键/鼠标事件的传输目标。
 * <p>
 * 由 {@link InputRouter} 决定每个输入事件应该被路由到哪里。
 * 可扩展：新增目标只需添加枚举值，对应路由逻辑在 Mixin 中处理。
 */
public enum InputTarget {
    /** 放行，原样交给游戏正常处理 */
    GAME,
    /** 拦截，但更新我们自己关注的 KeyMapping 状态（如跳过键） */
    SELF,
    /** 完全拦截，不传给任何人 */
    BLOCK
}
