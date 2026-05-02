package com.immersivecinematics.immersive_cinematics.script;

/**
 * 速度曲线类型枚举 — 控制关键帧间速度 v(t) 如何随时间变化
 * <p>
 * 在速度驱动模型下，插值曲线不再是"将 t 映射为 adjustedT"，
 * 而是"将 t 映射为瞬时速度 v(t)"，然后通过积分得到弧长进度 s。
 * <p>
 * 只保留两种类型：
 * <ul>
 *   <li>{@link #LINEAR} — 速度线性变化（匀速是最常用的子情况）</li>
 *   <li>{@link #SMOOTH} — Hermite 样条速度曲线，C¹ 连续，支持 curve_bias 控制弯曲方向</li>
 * </ul>
 */
public enum InterpolationType {

    /**
     * 线性速度变化
     * <p>
     * v(t) = s0 + (s1 - s0) * t
     * <p>
     * 当 speed₀ = speed₁ = 1.0 时为匀速，是最常用的情况。
     */
    LINEAR,

    /**
     * Hermite 样条速度曲线
     * <p>
     * 基于厄米基函数实现，天然满足 C¹ 连续。
     * 通过 curve_bias 控制关键帧处的切线方向，实现 easeIn/easeOut 效果。
     * <p>
     * v(t) = h₀₀(t)·sᵢ + h₁₀(t)·pᵢ + h₀₁(t)·sᵢ₊₁ + h₁₁(t)·pᵢ₊₁
     */
    SMOOTH
}
