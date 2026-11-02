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
}
