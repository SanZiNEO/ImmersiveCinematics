package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端脚本缓存：{@code CinematicScript} 按 id 存放的静态容器。
 *
 * 读写契约：写入以 id 覆盖旧值；读取未下发过的 id 返回 {@code null}。
 * 条目无自动淘汰，只在 {@link #clear()} 时整体清空。
 *
 * 写入方 = {@code ClientScriptReceiver}（服务端下发的播放 / 重载包解析成功后落缓存）；
 * 读取方 = {@code PlaybackApi.scripts()} 与 {@code PlaybackApi.play(String, String)}。
 */
public class ClientScriptCache {

    private static final Map<String, CinematicScript> CACHE = new HashMap<>();

    /** 写入脚本；同 id 覆盖旧值。 */
    public static void putScript(String id, CinematicScript script) {
        CACHE.put(id, script);
    }

    /** 取脚本；未缓存返回 {@code null}。 */
    public static CinematicScript getScript(String id) {
        return CACHE.get(id);
    }

    /** 批量写入；同 id 覆盖。 */
    public static void putAll(Map<String, CinematicScript> scripts) {
        CACHE.putAll(scripts);
    }

    /** 清空全部条目。 */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * 不可变快照（id → 脚本）；调用方不得依赖实时性，也不得修改返回值。
     * 供对外查询面（{@code PlaybackApi.scripts()}）在遍历期间不受并发写入影响。
     */
    public static Map<String, CinematicScript> snapshot() {
        return Map.copyOf(CACHE);
    }

    public static int size() { return CACHE.size(); }
}
