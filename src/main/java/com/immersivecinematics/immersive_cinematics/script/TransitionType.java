package com.immersivecinematics.immersive_cinematics.script;

/**
 * 片段过渡类型 — 与上一个片段的衔接方式
 * <p>
 * 替代原先的 String 类型（"cut"/"crossfade"），提供编译时类型安全。
 * JSON 中的小写值通过 {@code valueOf(value.toUpperCase())} 映射到枚举。
 */
public enum TransitionType {
    /** 硬切换：staged → commitStagedState 原子替换 */
    CUT,
    /** 交叉淡化：在两个片段的重叠区间内按比例混合所有属性 */
    CROSSFADE
}
