package com.immersivecinematics.immersive_cinematics.webui;

/**
 * 编辑器预览固定参数：1920×1080@60、2s 关键帧、允许 H.264；无 ffmpeg 时回退原始 RGBA。
 *
 * <p>2026-11-09 用户裁决：预览只传输 1080p60，不再提供 original / 720p 档位与可配帧率。
 * 这是一个固定值对象，没有解析入口。</p>
 */
public record PreviewConfig(int width, int height, int fps,
                            int keyframeIntervalSeconds, boolean encoderEnabled) {

    /** 固定值：1920×1080@60、2s 关键帧、允许编码。 */
    public static PreviewConfig fixed() {
        return new PreviewConfig(1920, 1080, 60, 2, true);
    }

    /** 线上名称固定为 {@code 1080p}。 */
    public String wireName() {
        return "1080p";
    }
}
