package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.script.event.EventClipRuntime;
import com.immersivecinematics.immersive_cinematics.script.track.TrackDriver;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * ModEvent 轨道播放器：占位实现，恒不活跃（模组事件轨道暂不驱动任何效果）。
 *
 * <p>格式与编译仍然完整：{@link #runtimeFor(Clip)} 返回该片段 {@code objects.mod_event} 的
 * 编译结果（记录 + 稳定身份 + 游标），第三方以后可以接；本播放器自身不消费、不推进游标。</p>
 */
public class ModEventTrackPlayer implements TrackDriver {

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;
    /** 每片段一份编译结果，按片段对象身份缓存。 */
    private final Map<Clip, EventClipRuntime> runtimes = new IdentityHashMap<>();
    /** 本播放器的实例令牌（事件身份的一部分）。 */
    private final Object instanceToken = new Object();

    public ModEventTrackPlayer(ScriptPlayer scriptPlayer, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
    }

    /**
     * 该片段的 MOD_EVENT 编译结果（同一片段多次调用返回同一实例）。
     * 只编译、不推进：{@link EventClipRuntime#advance(double)} 由未来的消费者调用。
     */
    public EventClipRuntime runtimeFor(Clip clip) {
        if (clip == null) return null;
        EventClipRuntime runtime = runtimes.get(clip);
        if (runtime == null) {
            runtime = EventClipRuntime.compile(clip, instanceToken);
            runtimes.put(clip, runtime);
        }
        return runtime;
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
        runtimes.clear();
    }
}
