package com.immersivecinematics.immersive_cinematics.editor;

public class EditorPlayback {
    private float time;
    private boolean playing;
    private long lastTick;

    public float getTime() { return time; }
    public boolean isPlaying() { return playing; }

    public void play() {
        playing = true;
        lastTick = System.currentTimeMillis();
    }

    public void pause() {
        playing = false;
    }

    public void stop() {
        playing = false;
        time = 0;
    }

    public void setTime(float t) {
        time = t;
    }

    /** Advance time if playing. Returns true if time changed. */
    public boolean tick(float totalDuration) {
        if (!playing) return false;
        long now = System.currentTimeMillis();
        time += (now - lastTick) / 1000f;
        lastTick = now;
        if (time >= totalDuration) {
            time = totalDuration;
            playing = false;
        }
        return true;
    }
}
