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
 *
 * 键 = {@link Key}（调用点 + 锚点来源 + 选择器字符串）三分量。同一 selector 在不同调用点 / 不同锚点来源
 * 各占一份条目、互不覆盖（1vN 场景下 follow 与 look_at 各持自己锚点解析出的结果）。
 *
 * 只保存服务端回传的 UUID，不引用任何客户端实体类；UUID → 客户端实体由调用方
 * （{@code EntityTargetResolver}）映射。本类只负责缓存与 pending 去重。
 *
 * 失效语义：键三分量任一变化 = 旧键不再命中，无需显式清理；条目不因目标死亡 / 卸载 / 维度切换被删除，
 * 由调用方时效兜底——{@code Entry.resolvedAt} + 调用点扫描间隔（{@code selector_refresh}）与重试节流。
 *
 * 线程：仅客户端主线程调用；{@code clear()} 在脚本停止 / 替换时清空全部条目。
 */
public final class ClientEntitySelectorCache {

    private static final int MAX_PENDING = 64;

    /**
     * 缓存键三分量；三分量逐一相等才算同一键。
     *
     * @param callpoint 调用点（角色）名，如 {@code follow} / {@code look_at}
     * @param anchorId  锚点来源身份（非逐帧坐标）：同一来源 = 同一键
     * @param selector  选择器字符串；也是发往服务端解析的原文
     */
    public record Key(String callpoint, String anchorId, String selector) {}

    /** 单个键的解析结果；{@code uuids} / {@code error} = 最近一次回包内容，{@code resolvedAt} = 该回包时间（毫秒）。 */
    public static final class Entry {
        public volatile List<UUID> uuids = Collections.emptyList();
        public volatile long resolvedAt;
        public volatile boolean pending;
        public volatile String error;
    }

    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Key> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_REQUEST_ID = new AtomicInteger(1);

    private ClientEntitySelectorCache() {
    }

    /** 按键取条目；{@code null} = 该键尚无条目（从未请求过）。 */
    public static Entry get(Key key) {
        return key == null ? null : CACHE.get(key);
    }

    /**
     * 请求服务端解析该键的选择器。同一键已有 pending 请求时不重复发送；待发请求数达
     * {@link #MAX_PENDING} 时本次请求被丢弃（不排队）。只在客户端主线程调用。
     */
    public static void request(Key key, double x, double y, double z) {
        if (key == null || key.selector() == null || key.selector().isEmpty()) {
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            return;
        }

        Entry entry = CACHE.computeIfAbsent(key, k -> new Entry());
        synchronized (entry) {
            if (entry.pending) {
                return;
            }
            entry.pending = true;
        }

        int requestId = NEXT_REQUEST_ID.getAndIncrement();
        PENDING.put(requestId, key);
        try {
            NetworkHandler.sendToServer(new C2SResolveEntitySelectorPacket(requestId, key.selector(), x, y, z));
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
        Key key = PENDING.remove(requestId);
        if (key == null) {
            return;
        }
        Entry entry = CACHE.get(key);
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
