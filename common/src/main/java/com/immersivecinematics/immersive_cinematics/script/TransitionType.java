package com.immersivecinematics.immersive_cinematics.script;

/**
 * 片段过渡类型 — 与上一个片段的衔接方式
 * <p>
 * 替代原先的 String 类型（"cut"/"morph"），提供编译时类型安全。
 * JSON 中的小写值通过 {@code valueOf(value.toUpperCase())} 映射到枚举。
 */
public enum TransitionType {
    /** 硬切换：staged → commitStagedState 原子替换 */
    CUT,
    /** morph 过渡：在 transition_duration 内从上一片段末帧线性飞向下一片段首帧（位置+角度） */
    MORPH
}
