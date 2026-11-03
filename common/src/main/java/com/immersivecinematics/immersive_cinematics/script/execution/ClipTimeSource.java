package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 轨道求值只读的时间来源：活跃窗与执行局部时间统一由播放控制（执行账本）给出，
 * 各轨不再从全局时钟自行做除法 / 取模 / 折叠。
 *
 * <p>{@link #finiteWindowEnd(Clip)} 只在片段有有限展开末端时有效——调用方必须先判
 * {@link Clip#isEffectivelyInfinite()}（或账本 {@code hasFiniteWindowEnd}），无限片段调用抛
 * {@link IllegalStateException}（编程错误，不用数值哨兵）。</p>
 */
public interface ClipTimeSource {

    /** 片段在时间轴读数 {@code head} 处是否活跃（含无限执行；不含已执行完的片段）。 */
    boolean isClipActive(Clip clip, double head);

    /** 片段在 {@code head} 处当前执行的局部时间（{@code [0, duration]}；{@code duration<0} 时 {@code >=0}）。 */
    double executionLocalTime(Clip clip, double head);

    /** 片段有限展开窗口末端（不含）；仅有限时有效。 */
    double finiteWindowEnd(Clip clip);

    /**
     * 片段当前执行在实例时间轴上的活跃窗起点（秒）：默认放置 = 轨道放置锚点，分支入口 = 跳转时刻。
     *
     * <p>Modifier 活跃窗（含 morph 前一段的延长判定）必须用本值，不能用 {@link Clip#getStartTime()}
     * ——分支入口下的执行起点不是片段默认放置锚点。默认实现回落 {@code clip.getStartTime()}，
     * 供不持有执行账本的轻量实现使用；播放器实现必须读执行账本。</p>
     */
    default double windowStart(Clip clip) {
        return clip != null ? clip.getStartTime() : 0.0;
    }
}
