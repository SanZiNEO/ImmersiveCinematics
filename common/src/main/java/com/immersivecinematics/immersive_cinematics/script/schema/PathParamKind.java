package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.spi.PathParamDef;
import com.immersivecinematics.immersive_cinematics.spi.PathParams;

/**
 * 路径曲线参数的类型标记：{@link PathCurveRegistry} 按它把 JSON 值归一成 {@link PathParams} 里的 Java 值。
 *
 * 每种 kind 只接受一种 JSON 形态，类型不符 / 非有限数字一律抛
 * {@link PathCurveException}（不静默回落缺省）。
 */
public enum PathParamKind {

    /** 有限浮点数。 */
    NUMBER,

    /** 有限整数值（{@code 2} 与 {@code 2.0} 都接受，{@code 2.5} 拒绝）。 */
    INTEGER,

    /** JSON 布尔。 */
    BOOLEAN,

    /** 字符串，取值必须落在 {@link PathParamDef#enumValues()} 内。 */
    ENUM,

    /** 任意字符串。 */
    TEXT,

    /** 单个控制点：恰好 {@code {x,y,z}} 或恰好 {@code {dx,dy,dz}}。 */
    POINT,

    /** 控制点数组：每个元素同 {@link #POINT}。 */
    POINT_LIST,

    /** 有限数字数组。 */
    NUMBER_LIST,

    /** 向量：恰好 {@code {x,y,z}}（只有绝对形态，无相对分量）。 */
    VECTOR
}
