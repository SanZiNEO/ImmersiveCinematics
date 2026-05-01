package com.immersivecinematics.immersive_cinematics.script;

/**
 * 曲线组合模式 — 控制脚本级与片段/关键帧级插值曲线的组合方式
 * <p>
 * 两种模式：
 * <ul>
 *   <li>{@link #OVERRIDE} — 覆盖模式（默认）：片段/关键帧级覆盖脚本级默认值。
 *       当片段/关键帧指定了非 LINEAR 的插值类型时，使用它替代脚本级默认；
 *       当片段/关键帧为 LINEAR 时，回退到脚本级默认值。
 *       符合用户心智模型：meta.interpolation 是"片段级未指定时的默认值"。</li>
 *   <li>{@link #COMPOSED} — 数学组合模式：先应用脚本级曲线，再应用片段/关键帧级曲线。
 *       即 adjustedT = clipInterp(scriptInterp(t))。
 *       产生双重平滑效果，适用于需要更强烈缓入缓出的场景。</li>
 * </ul>
 */
public enum CurveCompositionMode {
    /** 覆盖模式：片段/关键帧级覆盖脚本级默认值 */
    OVERRIDE,
    /** 数学组合模式：先脚本级，再片段/关键帧级（双重平滑） */
    COMPOSED
}
