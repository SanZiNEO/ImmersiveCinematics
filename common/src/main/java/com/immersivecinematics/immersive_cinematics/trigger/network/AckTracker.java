package com.immersivecinematics.immersive_cinematics.trigger.network;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放控制包 ACK 跟踪器（N1）— 客户端/服务端共享静态实例（refId 随机唯一，无冲突）。
 * <p>
 * Architectury SimpleNetworkManager 纯 fire-and-forget（无确认/重传），
 * 这里自建：发送方 {@link #expect} 登记待确认包，接收方处理成功后 {@link #ack}；
 * 每 tick {@link #tick} 检查超时（2s）重发，最多 3 次后放弃。
 */
public final class AckTracker {

    private static final long TIMEOUT_MS = 2000;
    private static final int MAX_RETRY = 3;

    private static final Map<String, Long> sentAt = new ConcurrentHashMap<>();
    private static final Map<String, Integer> retries = new ConcurrentHashMap<>();
    private static final Map<String, Runnable> pending = new ConcurrentHashMap<>();

    private AckTracker() {}

    /** 登记待确认包；resend 为重新发送同一包的逻辑 */
    public static void expect(String refId, Runnable resend) {
        if (refId == null || refId.isEmpty()) return;
        pending.put(refId, resend);
        retries.put(refId, 0);
        sentAt.put(refId, System.currentTimeMillis());
    }

    /** 收到回执；空 refId（旧包/异常）直接忽略 */
    public static void ack(String refId) {
        if (refId == null || refId.isEmpty()) return;
        pending.remove(refId);
        retries.remove(refId);
        sentAt.remove(refId);
    }

    public static String newRefId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** 每 tick 调用（服务端：ScriptEventManager.onServerTick；客户端：ClientEventHandler.CLIENT_POST） */
    public static void tick() {
        long now = System.currentTimeMillis();
        for (String refId : new ArrayList<>(pending.keySet())) {
            if (now - sentAt.getOrDefault(refId, 0L) >= TIMEOUT_MS) {
                int r = retries.merge(refId, 1, Integer::sum);
                if (r > MAX_RETRY) {
                    pending.remove(refId);
                    retries.remove(refId);
                    sentAt.remove(refId);
                    continue;
                }
                Runnable resend = pending.get(refId);
                if (resend != null) {
                    resend.run();
                    sentAt.put(refId, now);
                }
            }
        }
    }
}
