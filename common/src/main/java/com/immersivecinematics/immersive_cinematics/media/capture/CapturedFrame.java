package com.immersivecinematics.immersive_cinematics.media.capture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一帧捕获结果，带引用计数与归还回调（用于捕获缓冲池）。
 *
 * <p>像素契约：RGBA8，{@code rgba.length == width * height * 4}，行序<b>自顶向下</b>
 * （第 0 行 = 屏幕顶部，最后一行 = 屏幕底部）。</p>
 *
 * <p>时间戳 {@code ptsNanos} 取自 {@link System#nanoTime()}；{@code frameId} 由捕获层自 0 起单调自增。</p>
 *
 * <p>生命周期：构造时引用计数为 1，归投递方所有。任何需要跨线程/跨调用持有该帧的消费者<b>必须</b>
 * 先 {@link #retain()}，用完 {@link #release()}。计数归零时执行构造时传入的 {@code releaser}
 * （捕获层用它把 {@code rgba} 归还缓冲池）；{@code releaser} 为 {@code null} 表示普通堆数组，无需归还。</p>
 *
 * <p>{@link #rgba()} 返回的数组在计数归零后可能被复用，调用方不得在 {@link #release()} 之后继续引用。</p>
 */
public final class CapturedFrame {

    private final byte[] rgba;
    private final int width;
    private final int height;
    private final long ptsNanos;
    private final long frameId;
    private final Runnable releaser;
    private final AtomicInteger refCount = new AtomicInteger(1);

    private CapturedFrame(byte[] rgba, int width, int height, long ptsNanos, long frameId, Runnable releaser) {
        if (rgba == null) {
            throw new IllegalArgumentException("rgba must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size: " + width + "x" + height);
        }
        long expected = (long) width * (long) height * 4L;
        if (rgba.length != expected) {
            throw new IllegalArgumentException(
                    "rgba length mismatch: expected " + expected + " but got " + rgba.length
                            + " for " + width + "x" + height);
        }
        this.rgba = rgba;
        this.width = width;
        this.height = height;
        this.ptsNanos = ptsNanos;
        this.frameId = frameId;
        this.releaser = releaser;
    }

    /**
     * 创建一帧，引用计数为 1。
     *
     * @param rgba     像素数据（RGBA8，自顶向下，长度必须为 {@code width * height * 4}）
     * @param width    宽度（像素，&gt; 0）
     * @param height   高度（像素，&gt; 0）
     * @param ptsNanos 采集时刻（{@link System#nanoTime()}）
     * @param frameId  帧序号，自 0 起单调自增
     * @param releaser 计数归零时执行（通常把 {@code rgba} 归还缓冲池）；{@code null} 表示无需归还
     * @return 新帧，引用计数 1
     */
    public static CapturedFrame of(byte[] rgba, int width, int height, long ptsNanos, long frameId,
                                   Runnable releaser) {
        return new CapturedFrame(rgba, width, height, ptsNanos, frameId, releaser);
    }

    /** 像素数据（RGBA8，自顶向下）。计数归零后内容不可再读。 */
    public byte[] rgba() {
        return rgba;
    }

    /** 宽度（像素）。 */
    public int width() {
        return width;
    }

    /** 高度（像素）。 */
    public int height() {
        return height;
    }

    /** 采集时刻（{@link System#nanoTime()}）。 */
    public long ptsNanos() {
        return ptsNanos;
    }

    /** 帧序号，自 0 起单调自增。 */
    public long frameId() {
        return frameId;
    }

    /**
     * 引用计数 +1，返回 {@code this}。
     *
     * @return {@code this}
     * @throws IllegalStateException 该帧已被完全释放（计数已归零）
     */
    public CapturedFrame retain() {
        int current;
        do {
            current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("frame already released: " + frameId);
            }
        } while (!refCount.compareAndSet(current, current + 1));
        return this;
    }

    /**
     * 引用计数 -1；归零时执行 {@code releaser}。
     *
     * @throws IllegalStateException 重复释放（计数已归零）
     */
    public void release() {
        int current = refCount.decrementAndGet();
        if (current < 0) {
            throw new IllegalStateException("frame released more than retained: " + frameId);
        }
        if (current == 0) {
            Runnable r = releaser;
            if (r != null) {
                r.run();
            }
        }
    }
}
