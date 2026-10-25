package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络包处理边界 — 平台适配层统一用本类包裹 {@code packet.handle(...)}。
 *
 * 单个坏包（异常字段 / 半更新的服务端状态 / 客户端退出世界）只丢弃本包并限频记日志，
 * 不中断连接、不打断 tick；致命错误（OOM / 类链接失败 / GL 上下文丢失等）原样上抛，
 * 非致命 {@link Error} 也视为编程错误上抛，避免把 JVM 级故障伪装成业务降级。
 */
public final class PacketGuard {

    private static final long LOG_INTERVAL_MS = 5000L;

    private PacketGuard() {}

    /** 带玩家上下文的包处理边界（服务端包用）。 */
    public static void handle(String direction, String packet, ServerPlayer player, Runnable body) {
        guarded(direction, packet, player, body);
    }

    /** 无玩家上下文的包处理边界（客户端包用）。 */
    public static void handle(String direction, String packet, Runnable body) {
        guarded(direction, packet, null, body);
    }

    private static void guarded(String direction, String packet, ServerPlayer player, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            String where = player == null ? ""
                    : "，玩家: " + player.getGameProfile().getName() + "/" + player.getUUID();
            ErrorLog.logRateLimited("Net", "packet:" + direction + ":" + packet, LOG_INTERVAL_MS,
                    "网络包处理异常（边界: " + direction + "，包: " + packet + where
                            + "）：本包已丢弃，连接与播放继续", e);
        }
    }
}
