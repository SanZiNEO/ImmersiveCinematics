package com.immersivecinematics.immersive_cinematics.media.capture;

/**
 * 一帧捕获结果。
 *
 * <p>像素契约：RGBA8，{@code rgba.length == width * height * 4}，行序<b>自顶向下</b>
 * （第 0 行 = 屏幕顶部，最后一行 = 屏幕底部）。</p>
 *
 * <p>时间戳 {@code ptsNanos} 取自 {@link System#nanoTime()}；{@code frameId} 由捕获层自 0 起单调自增。</p>
 *
 * @param rgba      像素数据（RGBA8，自顶向下）
 * @param width     宽度（像素）
 * @param height    高度（像素）
 * @param ptsNanos  采集时刻（{@link System#nanoTime()}）
 * @param frameId   帧序号，自 0 起单调自增
 */
public record CapturedFrame(byte[] rgba, int width, int height, long ptsNanos, long frameId) {

    public CapturedFrame {
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
    }
}
