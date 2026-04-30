package com.immersivecinematics.immersive_cinematics.script;

/**
 * 插值作用域 — 控制插值曲线映射的应用范围
 * <p>
 * <ul>
 *   <li>{@link #CLIP} — 曲线映射应用到整体 clip 进度，关键帧段内线性插值。
 *        整个 clip 只在开头/结尾有缓入缓出，中间关键帧处速度连续。</li>
 *   <li>{@link #SEGMENT} — 曲线映射应用到每个关键帧段（传统行为）。
 *        每段独立缓入缓出，中间关键帧处速度归零后重新加速。</li>
 * </ul>
 * <p>
 * 示例：3 个关键帧的 clip，interpolation=smooth
 * <pre>
 *   CLIP scope:    v=0 ────加速──── v=max ────减速──── v=0
 *                  KF1 ────────────────────────────── KF3
 *                            KF2 (速度连续)
 *
 *   SEGMENT scope: v=0 → v=0   v=0 → v=0
 *                  KF1 ── KF2 ── KF3
 *                    停顿    停顿
 * </pre>
 */
public enum InterpolationScope {
    /** 整体 clip 进度映射 — 中间关键帧处速度连续 */
    CLIP,
    /** 逐段映射 — 每段独立缓入缓出（传统行为） */
    SEGMENT
}
