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
 * 现状：全工程无调用方，未接入播放链路。
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

    public static int size() { return CACHE.size(); }
}
