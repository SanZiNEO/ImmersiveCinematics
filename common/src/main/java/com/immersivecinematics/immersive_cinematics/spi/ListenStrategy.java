package com.immersivecinematics.immersive_cinematics.trigger.server;

/**
 * 触发器监听策略：决定该类型由事件回调驱动，还是由 {@code TriggerEngine} 按间隔轮询。
 */
public enum ListenStrategy {
    /** 由游戏事件回调直接驱动（如 {@code advancement} / {@code entity_kill}），不参与轮询。 */
    EVENT_DRIVEN,
    /** 在服务端 tick 中按生效轮询间隔求值（如 {@code observation} / {@code inventory}）。 */
    POLLING
}
