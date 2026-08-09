package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * C2S 包发送防护:玩家随时可能退出游戏/断线,此时 Architectury 的
 * {@code sendToServer()} 会抛 {@code IllegalStateException}("Unable to send packet
 * to the server while not in game!")——在渲染线程未捕获会导致游戏崩溃。
 * 所有客户端→服务端发包统一经此发送,异常只记录日志、不向上抛。
 */
public final class NetworkGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NetworkGuard() {}

    /**
     * 安全发送 C2S 包。
     *
     * @param what 包用途描述(日志用,如 "C2SScriptPause")
     * @param send 发包动作(通常是 xxxPacket.sendToServer())
     */
    public static void sendToServer(String what, Runnable send) {
        try {
            send.run();
        } catch (Exception e) {
            LOGGER.warn("发送 C2S 包失败(可能已断线/退出世界),已忽略: {} - {}", what, e.getMessage());
        }
    }
}
