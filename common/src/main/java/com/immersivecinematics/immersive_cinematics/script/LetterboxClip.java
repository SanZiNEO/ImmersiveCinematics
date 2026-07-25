package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;

public class LetterboxClip {

    private final float startTime;
    private final float duration;
    private final List<LetterboxKeyframe> keyframes;

    public LetterboxClip(float startTime, float duration, List<LetterboxKeyframe> keyframes) {
        this.startTime = startTime;
        this.duration = duration;
        this.keyframes = keyframes;
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public List<LetterboxKeyframe> getKeyframes() { return keyframes; }

    public boolean isInfinite() { return duration < 0f; }

    @Override
    public String toString() {
        return String.format("LetterboxClip{start=%.2f, dur=%.2f, keyframes=%d}",
                startTime, duration, keyframes != null ? keyframes.size() : 0);
    }
}
