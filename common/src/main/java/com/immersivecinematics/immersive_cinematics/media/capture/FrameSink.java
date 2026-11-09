package com.immersivecinematics.immersive_cinematics.media.capture;

/**
 * 捕获层的帧消费者。
 *
 * <p>调用线程：渲染线程（{@link FrameCapture#capture} 内部同步调用）。</p>
 *
 * <p>采样与背压策略由实现方决定；捕获层不做节流/排队，但缓冲池被下游占满时会丢弃本帧并计数（见
 * {@link FrameCapture#droppedFrames()}）。</p>
 */
public interface FrameSink {

    /**
     * 接收一帧捕获结果。渲染线程调用，实现方需自行保证不阻塞渲染。
     *
     * @param frame 捕获帧（RGBA8、自顶向下）
     */
    void onFrame(CapturedFrame frame);
}
