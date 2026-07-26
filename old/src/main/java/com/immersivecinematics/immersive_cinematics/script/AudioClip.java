package com.immersivecinematics.immersive_cinematics.script;

/**
 * 音频片段 — 背景音乐/音效
 * <p>
 * 音频轨道不限制数量，多音频轨道 = 多声道混音。
 */
public class AudioClip {

    /** 总时间轴上的开始时间（秒） */
    private final float startTime;

    /** 持续时长（秒） */
    private final float duration;

    /** 音效资源ID（如 "minecraft:ambient.cave"） */
    private final String sound;

    /** 音量（0.0-1.0） */
    private final float volume;

    /** 音高（0.5-2.0） */
    private final float pitch;

    /** 是否循环 */
    private final boolean loop;

    /** 淡入时长（秒） */
    private final float fadeIn;

    /** 淡出时长（秒） */
    private final float fadeOut;

    public AudioClip(float startTime, float duration, String sound, float volume,
                     float pitch, boolean loop, float fadeIn, float fadeOut) {
        this.startTime = startTime;
        this.duration = duration;
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
        this.loop = loop;
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public String getSound() { return sound; }
    public float getVolume() { return volume; }
    public float getPitch() { return pitch; }
    public boolean isLoop() { return loop; }
    public float getFadeIn() { return fadeIn; }
    public float getFadeOut() { return fadeOut; }

    @Override
    public String toString() {
        return String.format("AudioClip{start=%.2f, dur=%.2f, sound=%s, vol=%.2f, pitch=%.2f, loop=%s}",
                startTime, duration, sound, volume, pitch, loop);
    }
}
