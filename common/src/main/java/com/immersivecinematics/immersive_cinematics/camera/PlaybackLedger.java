package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptNotifier;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPlaybackStartedPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptPausePacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;

/**
 * 播放账本（网络侧）：播放开始回执、暂停/恢复握手、结束通知与跳过投票复位。
 *
 * 只记游戏播放侧的账：编辑器预览实例不上报（由 {@link PlaybackInstance#isPreview()} 门控）。
 * 开始回执只在「实际开始播放」的入口调用（排队等待 / 被拒绝 / 预览都不上报）；
 * 暂停握手按实例发一条 + 登记 ACK 超时重发，触发时机 = 帧级暂停↔恢复转换。
 *
 * 线程：仅客户端主线程；发包只在事件帧发生。
 */
final class PlaybackLedger {

    /** 上一帧的暂停状态，用于检测暂停↔恢复的转换。 */
    private boolean lastFramePaused = false;

    /**
     * 脚本真正开始播放时发 {@code C2SPlaybackStarted}（{@code started = true}）。
     *
     * @param script  已开始播放的脚本；{@code null} 或 id 为空 = 不上报
     * @param started 本次刚启动的实例（取其播放实例 id）；{@code null} = 上报空 id
     */
    void reportPlaybackStarted(CinematicScript script, PlaybackInstance started) {
        if (script == null) return;
        String id = script.getId();
        if (id == null || id.isEmpty()) return;
        final String instanceId = started != null && started.instanceId() != null ? started.instanceId() : "";
        NetworkGuard.sendToServer("C2SPlaybackStarted",
                () -> NetworkHandler.sendToServer(new C2SPlaybackStartedPacket(id, instanceId, true)));
    }

    /** 暂停/恢复握手（按实例）：立即发一条 + 登记 ACK 超时重发（服务端处理幂等）。 */
    void sendPausePacket(String scriptId, String instanceId, boolean paused) {
        String refId = AckTracker.newRefId();
        AckTracker.expect(refId,
                () -> NetworkGuard.sendToServer("C2SScriptPause",
                        () -> NetworkHandler.sendToServer(
                                new C2SScriptPausePacket(scriptId, instanceId, paused, refId))));
        NetworkGuard.sendToServer("C2SScriptPause",
                () -> NetworkHandler.sendToServer(
                        new C2SScriptPausePacket(scriptId, instanceId, paused, refId)));
    }

    /** 本帧暂停↔恢复是否发生转换，并记录本帧暂停态（只跟游戏暂停）。 */
    boolean pauseTransition(boolean gamePaused) {
        boolean transition = gamePaused != lastFramePaused;
        if (transition) {
            lastFramePaused = gamePaused;
        }
        return transition;
    }

    /**
     * 结束通知 + 跳过投票复位；两者都只对游戏实例生效（预览实例退出不动游戏播放侧的账）。
     *
     * @param instance 刚停用的实例；{@code null} = 无实例可通知（仍复位跳过投票）
     * @param reason   上报的完成原因
     */
    void reportScriptFinished(PlaybackInstance instance, CompletionReason reason) {
        boolean preview = instance != null && instance.isPreview();
        String finishedScriptId = instance != null ? instance.scriptId() : null;
        if (finishedScriptId != null && !preview) {
            String finishedInstanceId = instance.instanceId() != null ? instance.instanceId() : "";
            ClientScriptNotifier.notifyScriptFinished(finishedScriptId, finishedInstanceId, reason);
        }
        if (!preview) {
            ClientScriptReceiver.resetSkipVote();
        }
    }
}
