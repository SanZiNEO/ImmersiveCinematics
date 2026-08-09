package com.immersivecinematics.immersive_cinematics.script;

/**
 * ModEvent 轨道播放器 — 占位实现
 * <p>
 * Phase 1 不实现模组事件轨道播放，后续迭代补充。
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
        // Phase 1: 模组事件轨道暂不实现
        return false;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        // Phase 1: 模组事件轨道暂不实现
    }

    @Override
    public void onStop() {
        // Phase 1: 模组事件轨道暂不实现
    }
}
