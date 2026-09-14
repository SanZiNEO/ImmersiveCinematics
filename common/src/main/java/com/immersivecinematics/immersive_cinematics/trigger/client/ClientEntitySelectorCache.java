package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.trigger.network.C2SResolveEntitySelectorPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端 NBT / 复杂实体选择器解析缓存。
 * <p>
 * 只保存服务端回传的 UUID，不引用任何客户端实体类。
 * 相机跟拍/注视仍在 {@code CameraTrackPlayer} 里把 UUID 映射成客户端实体。
 */
public final class ClientEntitySelectorCache {

    /** 解析结果有效期；与本地 selector 缓存保持一致。 */
    public static final long TTL_MS = 1000L;

    private static final int MAX_PENDING = 64;

    public static final class Entry {
        public volatile List<UUID> uuids = Collections.emptyList();
        public volatile long resolvedAt;
        public volatile boolean pending;
        public volatile String error;
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, String> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_REQUEST_ID = new AtomicInteger(1);

    private ClientEntitySelectorCache() {
    }

    public static Entry get(String selector) {
        return selector == null ? null : CACHE.get(selector);
    }

    /**
     * 请求服务端解析 selector。同一 selector 已有 pending 请求时不会重复发送。
     * 只在客户端主线程调用。
     */
    public static void request(String selector, double x, double y, double z) {
        if (selector == null || selector.isEmpty()) {
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            return;
        }

        Entry entry = CACHE.computeIfAbsent(selector, key -> new Entry());
        synchronized (entry) {
            if (entry.pending) {
                return;
            }
            entry.pending = true;
        }

        int requestId = NEXT_REQUEST_ID.getAndIncrement();
        PENDING.put(requestId, selector);
        try {
            NetworkHandler.sendToServer(new C2SResolveEntitySelectorPacket(requestId, selector, x, y, z));
        } catch (Exception e) {
            PENDING.remove(requestId);
            synchronized (entry) {
                entry.pending = false;
            }
        }
    }

    /**
     * 服务端回包入口。由 {@code S2CResolveEntitySelectorResultPacket} 在客户端线程调用。
     */
    public static void onResult(int requestId, boolean success, String error, List<UUID> uuids) {
        String selector = PENDING.remove(requestId);
        if (selector == null) {
            return;
        }
        Entry entry = CACHE.get(selector);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.pending = false;
            entry.error = success ? null : error;
            entry.uuids = uuids != null ? uuids : Collections.emptyList();
            entry.resolvedAt = System.currentTimeMillis();
        }
    }

    /** 脚本停止/替换时清缓存，避免跨脚本复用旧 UUID。 */
    public static void clear() {
        CACHE.clear();
        PENDING.clear();
    }
}
