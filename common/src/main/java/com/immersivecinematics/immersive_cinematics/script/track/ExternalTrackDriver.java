package com.immersivecinematics.immersive_cinematics.script.track;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExecution;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

/**
 * 第三方轨驱动：把 {@code ScriptPlayer} 的调度（全局时间）翻译成片段的
 * {@link com.immersivecinematics.immersive_cinematics.spi.TrackPlayer} 生命周期回调。
 *
 * <p>活跃片段 = 本轨第一个 {@link ScriptPlayer#isClipActive} 为真的片段（顺序同轨道内顺序）。
 * 片段切换 / 离开活跃窗时先 {@code stop} 再清状态；seek / 循环折返 / 脚本替换按「先停、下一帧重新 start」
 * 处理。回调抛出的异常一律限频记录并吞掉——单条第三方轨不得中断其它轨道的逐帧驱动。</p>
 */
final class ExternalTrackDriver implements TrackDriver {

    /** 异常限频键（按轨道 id 分桶）。 */
    private static final long LOG_INTERVAL_MS = 5000L;

    private final TrackTypeId id;
    private final ScriptPlayer player;
    private final int trackIndex;
    private final com.immersivecinematics.immersive_cinematics.spi.TrackPlayer impl;

    private Clip activeClip;
    private TrackContextImpl ctx;

    ExternalTrackDriver(TrackTypeId id, ScriptPlayer player, int trackIndex,
                        com.immersivecinematics.immersive_cinematics.spi.TrackPlayer impl) {
        this.id = id;
        this.player = player;
        this.trackIndex = trackIndex;
        this.impl = impl;
    }

    @Override
    public boolean isActiveAt(double globalTime) {
        for (Clip clip : player.clipsForTrack(trackIndex)) {
            if (player.isClipActive(clip, globalTime)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRenderFrame(double globalTime) {
        Clip active = findActiveClip(globalTime);
        if (active == null) {
            if (activeClip != null) {
                stopQuietly();
                activeClip = null;
                ctx = null;
            }
            return;
        }
        if (active != activeClip) {
            if (activeClip != null) {
                stopQuietly();
            }
            activeClip = active;
            ctx = new TrackContextImpl(id, active, player, trackIndex);
            startQuietly();
        }
        double localTime = player.executionLocalTime(active, globalTime);
        int executionIndex = executionIndex(active);
        ctx.refresh(localTime, executionIndex);
        tickQuietly();
    }

    @Override
    public void onStop() {
        stopQuietly();
        activeClip = null;
        ctx = null;
    }

    @Override
    public void onSeek() {
        onStop();
    }

    @Override
    public void onLoopRestart() {
        onStop();
    }

    @Override
    public void onScriptReplaced() {
        onStop();
    }

    private Clip findActiveClip(double globalTime) {
        for (Clip clip : player.clipsForTrack(trackIndex)) {
            if (player.isClipActive(clip, globalTime)) {
                return clip;
            }
        }
        return null;
    }

    private int executionIndex(Clip clip) {
        ClipExecution execution = player.executions().executionOf(clip);
        return execution != null ? execution.executionIndex() : 0;
    }

    private void startQuietly() {
        if (ctx == null) {
            return;
        }
        try {
            impl.start(ctx);
        } catch (Exception e) {
            log("start failed", e);
        }
    }

    private void tickQuietly() {
        if (ctx == null) {
            return;
        }
        try {
            impl.tick(ctx);
        } catch (Exception e) {
            log("tick failed", e);
        }
    }

    private void stopQuietly() {
        if (ctx == null) {
            return;
        }
        try {
            impl.stop(ctx);
        } catch (Exception e) {
            log("stop failed", e);
        }
    }

    private void log(String what, Exception e) {
        ErrorLog.logRateLimited("Track", "third-party-track:" + id.id(), LOG_INTERVAL_MS,
                "third-party track " + what + ": " + id.id(), e);
    }
}
