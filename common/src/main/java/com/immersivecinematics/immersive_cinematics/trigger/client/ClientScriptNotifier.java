package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptFinishedPacket;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class ClientScriptNotifier {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void notifyScriptFinished(String scriptId, CompletionReason reason) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // 世界退出/断线时(如 emergencyStop 路径)连接已断开,发 C2S 包会抛
        // "Unable to send packet to the server while not in game!" 导致崩溃——断线时跳过,
        // 再由 NetworkGuard 兜底(连接检查与发送之间的竞态窗口)
        net.minecraft.client.multiplayer.ClientPacketListener conn = mc.getConnection();
        if (conn == null || conn.getConnection() == null || !conn.getConnection().isConnected()) {
            LOGGER.debug("跳过脚本结束通知(未连接服务器): {} reason={}", scriptId, reason);
            return;
        }
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer(
                "C2SScriptFinished", () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new C2SScriptFinishedPacket(scriptId, reason)));
        LOGGER.debug("Sent script finished notification: {} reason={}", scriptId, reason);
    }
}
