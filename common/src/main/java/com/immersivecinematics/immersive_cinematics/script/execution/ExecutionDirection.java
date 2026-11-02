package com.immersivecinematics.immersive_cinematics.script.execution;

/**
 * 单次执行的遍历方向：{@link #FORWARD} = 局部时间 0→duration；{@link #BACKWARD} = duration→0。
 *
 * <p>方向只由 {@code loop_mode} 与执行序号决定：{@code pingpong} 偶数次正向、奇数次反向；
 * {@code repeat} 恒正向。采样器不感知方向，只认已映射到 {@code [0, duration]} 的局部时间。</p>
 */
public enum ExecutionDirection {

    FORWARD,
    BACKWARD;

    /** 第 {@code executionIndex} 次执行的方向（{@code repeat} 恒正向）。 */
    public static ExecutionDirection of(LoopMode mode, int executionIndex) {
        return mode == LoopMode.PINGPONG && (executionIndex & 1) != 0 ? BACKWARD : FORWARD;
    }
}
