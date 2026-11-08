package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.script.track.TrackDriver;

/**
 * ModEvent 轨道播放器：占位实现，恒不活跃（模组事件轨道暂不驱动任何效果）。
 *
 * <p>格式与编译仍然完整：片段 {@code objects.mod_event} 的编译结果
 * （记录 + 稳定身份 + 游标）由第三方消费者自行编译；本播放器不消费、不推进游标。</p>
 */
public class ModEventTrackPlayer implements TrackDriver {

    public ModEventTrackPlayer(ScriptPlayer scriptPlayer, int trackIndex) {
    }

    @Override
    public boolean isActiveAt(double globalTime) {
        return false;
    }

    @Override
    public void onRenderFrame(double globalTime) {
    }

    @Override
    public void onStop() {
    }
}
