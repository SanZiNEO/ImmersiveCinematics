package com.immersivecinematics.immersive_cinematics.script;

/**
 * 黑边片段 — 画幅比黑边控制
 * <p>
 * 黑边独立时间轴的好处：
 * <ul>
 *   <li>用户可以设 fade_in:0 / fade_out:0 实现硬切黑边</li>
 *   <li>可以在脚本中间插入黑边片段（场景切换时黑边消失再出现）</li>
 *   <li>多段黑边可以有不同的画幅比（回忆场景用2.35，现实用1.778）</li>
 *   <li>不需要黑边的脚本直接不添加 letterbox 轨道</li>
 *   <li>黑边的开始/结束时间独立于镜头片段</li>
 * </ul>
 */
public class LetterboxClip {

    /** 总时间轴上的开始时间（秒） */
    private final float startTime;

    /** 持续时长（秒），负数=无限 */
    private final float duration;

    /** 是否启用黑边 */
    private final boolean enabled;

    /** 目标画幅比（宽/高），常见值：2.35（变形宽银幕）、2.0（2:1）、1.778（16:9） */
    private final float aspectRatio;

    /** 入场动画时长（秒），黑边从0高度缓动到目标高度，0=无动画即时出现 */
    private final float fadeIn;

    /** 退场动画时长（秒），黑边从目标高度缓动到0，0=无动画即时消失 */
    private final float fadeOut;

    public LetterboxClip(float startTime, float duration, boolean enabled,
                         float aspectRatio, float fadeIn, float fadeOut) {
        this.startTime = startTime;
        this.duration = duration;
        this.enabled = enabled;
        this.aspectRatio = aspectRatio;
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public boolean isEnabled() { return enabled; }
    public float getAspectRatio() { return aspectRatio; }
    public float getFadeIn() { return fadeIn; }
    public float getFadeOut() { return fadeOut; }

    /** 是否为无限时长片段 */
    public boolean isInfinite() { return duration < 0f; }

    @Override
    public String toString() {
        return String.format("LetterboxClip{start=%.2f, dur=%.2f, enabled=%s, ratio=%.3f, fadeIn=%.2f, fadeOut=%.2f}",
                startTime, duration, enabled, aspectRatio, fadeIn, fadeOut);
    }
}
