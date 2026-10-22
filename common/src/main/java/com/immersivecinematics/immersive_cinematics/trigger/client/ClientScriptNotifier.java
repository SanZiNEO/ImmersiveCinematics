package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptFinishedPacket;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 脚本结束通知（客户端 → 服务端）。
 *
 * 只对游戏实例发送：预览实例的退出由调用方 {@code PlaybackLedger} 过滤，不进服务端账本。
 * 未连接服务器时（断线 / 退出世界）跳过发送，避免网络层抛异常打断渲染线程。
 */
public class ClientScriptNotifier {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 上报某实例已结束及完成原因；未连接服务器时静默跳过（仅 debug 日志）。
     *
     * @param instanceId 实例标识；空串 = 未携带
     */
    public static void notifyScriptFinished(String scriptId, String instanceId, CompletionReason reason) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // 断线 / 退出世界时（如 emergencyStop 路径）连接已断，发 C2S 会抛
        // "Unable to send packet to the server while not in game!" 导致崩溃，故先查连接；
        // NetworkGuard 再兜底检查与发送之间的竞态窗口
        net.minecraft.client.multiplayer.ClientPacketListener conn = mc.getConnection();
        if (conn == null || conn.getConnection() == null || !conn.getConnection().isConnected()) {
            LOGGER.debug("跳过脚本结束通知(未连接服务器): {} reason={}", scriptId, reason);
            return;
        }
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer(
                "C2SScriptFinished", () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new C2SScriptFinishedPacket(scriptId, instanceId, reason, "")));
        LOGGER.debug("Sent script finished notification: {} instance={} reason={}", scriptId, instanceId, reason);
    }
}
