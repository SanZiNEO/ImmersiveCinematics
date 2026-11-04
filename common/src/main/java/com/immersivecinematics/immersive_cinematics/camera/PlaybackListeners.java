package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.api.PlaybackListener;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 播放监听器登记与广播（{@link PlaybackListener} 的内部落地）：{@link CameraManager} 收注册，
 * 生命周期起播点（{@link PlaybackLifecycle#startScriptInternal}）与结束点（{@link PlaybackLedger#reportScriptFinished}）广播。
 *
 * <p>线程：仅客户端主线程写入与广播；容器用 {@link CopyOnWriteArrayList} 只是为了广播期间的
 * 注册 / 移除不破坏遍历。广播逐个隔离异常——一个监听器抛异常不影响其它监听器，也不影响播放。
 *
 * <p>包私有：对外入口是 {@code api.PlaybackApi#addListener/removeListener}。
 */
final class PlaybackListeners {

    private static final CopyOnWriteArrayList<PlaybackListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 异常日志分类（控制台 / 文件里的 {@code [Playback]}）。 */
    private static final String ERROR_CATEGORY = "Playback";
    /** 异常日志限频键：所有回调异常共用一桶（同一失败点不同监听器不重复刷屏）。 */
    private static final String ERROR_KEY = "listener";
    private static final long ERROR_INTERVAL_MS = 5000L;

    private PlaybackListeners() {}

    /** 注册监听器：{@code null} 忽略；同一实例重复注册只保留一份。 */
    static void add(PlaybackListener listener) {
        if (listener == null) return;
        LISTENERS.addIfAbsent(listener);
    }

    /** 移除监听器：{@code true} = 确有该监听器被移除。 */
    static boolean remove(PlaybackListener listener) {
        return listener != null && LISTENERS.remove(listener);
    }

    /** 广播"脚本开始播放"；{@code null} id 归一为空串。 */
    static void started(String scriptId, String instanceId) {
        String sid = normalize(scriptId);
        String iid = normalize(instanceId);
        for (PlaybackListener listener : LISTENERS) {
            try {
                listener.onStarted(sid, iid);
            } catch (Throwable t) {
                ErrorLog.logRateLimited(ERROR_CATEGORY, ERROR_KEY, ERROR_INTERVAL_MS, "PlaybackListener 回调异常", t);
            }
        }
    }

    /** 广播"脚本结束播放"；{@code null} id 归一为空串。 */
    static void finished(String scriptId, String instanceId, CompletionReason reason) {
        String sid = normalize(scriptId);
        String iid = normalize(instanceId);
        for (PlaybackListener listener : LISTENERS) {
            try {
                listener.onFinished(sid, iid, reason);
            } catch (Throwable t) {
                ErrorLog.logRateLimited(ERROR_CATEGORY, ERROR_KEY, ERROR_INTERVAL_MS, "PlaybackListener 回调异常", t);
            }
        }
    }

    private static String normalize(String id) {
        return id == null ? "" : id;
    }
}
