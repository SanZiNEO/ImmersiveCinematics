package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * 路径锚点：说明调用点给定的端点如何参与曲线定义。
 * 锚点由路径类型决定，调用方据此决定 {@code bind(from, to)} 的哪些参数生效。
 */
public enum PathAnchor {

    /** 曲线首末点就是调用点给定的 {@code from} / {@code to}。 */
    ENDPOINTS,

    /** 起点取调用点给定的 {@code from}，终点由曲线自身参数决定。 */
    START,

    /** 首末点完全由曲线自身参数决定，与调用点端点无关。 */
    FREE
}
