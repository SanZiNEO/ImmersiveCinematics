package com.immersivecinematics.immersive_cinematics.math.path;

/**
 * 路径能力标记：曲线按需声明，调用方查询后选择解析快路径或通用算子。
 * 未声明的能力一律按“不支持”处理，不假设曲线具备。
 */
public enum PathCapability {

    /** 曲线实现 {@link AnalyticArcLength}，弧长算子直接调用而不建表。 */
    ANALYTIC_ARC_LENGTH,

    /** 曲线提供解析二阶导，曲率算子直接求而不做数值差分。 */
    ANALYTIC_SECOND_DERIVATIVE,

    /** 曲线参数以 {@link PathExpression#period()} 为周期，调用方先对 u 取模再求值。 */
    PERIODIC,

    /** 曲线控制点带有理权重，调用方按权重解释几何。 */
    RATIONAL_WEIGHTS,

    /** 曲线由节点向量定义，调用方按节点解释定义域与分段。 */
    KNOT_VECTOR
}
