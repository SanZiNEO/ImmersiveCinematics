package com.immersivecinematics.immersive_cinematics.script;

/**
 * ModEvent 轨道播放器：占位实现，恒不活跃（模组事件轨道暂不驱动任何效果）。
 */
public class ModEventTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;

    public ModEventTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return false;
    }

    @Override
    public void onRenderFrame(float globalTime) {
    }

    @Override
    public void onStop() {
    }
}
