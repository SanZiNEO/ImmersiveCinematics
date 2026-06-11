package com.immersivecinematics.immersive_cinematics.script;

public class LetterboxKeyframe {
    private final float time;
    private final float aspectRatio;

    public LetterboxKeyframe(float time, float aspectRatio) {
        this.time = time;
        this.aspectRatio = aspectRatio;
    }

    public float getTime() { return time; }
    public float getAspectRatio() { return aspectRatio; }

    @Override
    public String toString() {
        return String.format("LetterboxKf{time=%.2f, ratio=%.3f}", time, aspectRatio);
    }
}
