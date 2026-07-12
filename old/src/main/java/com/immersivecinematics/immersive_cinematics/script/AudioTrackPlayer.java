package com.immersivecinematics.immersive_cinematics.script;

/**
 * Audio 轨道播放器 — 占位实现
 * <p>
 * Phase 1 不实现音频轨道播放，后续迭代补充。
 */
public class AudioTrackPlayer implements TrackPlayer {

    private final TimelineTrack track;

    public AudioTrackPlayer(TimelineTrack track) {
        this.track = track;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        // Phase 1: 音频轨道暂不实现
        return false;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        // Phase 1: 音频轨道暂不实现
    }

    @Override
    public void onStop() {
        // Phase 1: 音频轨道暂不实现
    }
}
