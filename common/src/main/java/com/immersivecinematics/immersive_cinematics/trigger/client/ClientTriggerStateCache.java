package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.trigger.network.S2CTriggerStateSyncPacket;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Set;

/**
 * 客户端触发状态快照：服务端下发的「已触发触发器」与「已完成脚本」集合。
 *
 * 读：按脚本 id 查触发器集合 / 完成标记；未下发过的脚本返回 {@code null} / {@code false}。
 * 写：{@link #handleSync} 用整包内容整体替换（不合并），并直接引用包内集合、不复制——包在反序列化时
 * 新建且此后不再复用，故安全；返回值即内部集合，调用方只读。
 * 线程：仅客户端主线程（S2C 包经 {@code client.execute} 分发）。
 */
public class ClientTriggerStateCache {

    private static final ClientTriggerStateCache INSTANCE = new ClientTriggerStateCache();

    private Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> triggeredScripts = new Object2ObjectOpenHashMap<>();
    private ObjectOpenHashSet<String> completedScripts = new ObjectOpenHashSet<>();

    private ClientTriggerStateCache() {}

    /** 整体替换两份快照（服务端登录 / 状态变化时下发）。 */
    public static void handleSync(S2CTriggerStateSyncPacket packet) {
        INSTANCE.triggeredScripts = packet.getTriggeredScripts();
        INSTANCE.completedScripts = packet.getCompletedScripts();
    }

    /** 该脚本的该触发器是否已触发。 */
    public static boolean isTriggered(String scriptId, String triggerId) {
        Set<String> triggers = INSTANCE.triggeredScripts.get(scriptId);
        return triggers != null && triggers.contains(triggerId);
    }

    /** 该脚本是否已完成过（once 语义的判定依据）。 */
    public static boolean isScriptCompleted(String scriptId) {
        return INSTANCE.completedScripts.contains(scriptId);
    }

    /** 该脚本已触发的触发器集合；未下发过返回 {@code null}。 */
    public static Set<String> getTriggeredTriggers(String scriptId) {
        return INSTANCE.triggeredScripts.get(scriptId);
    }

    /** 已完成脚本集合（内部引用，只读）。 */
    public static ObjectOpenHashSet<String> getCompletedScripts() {
        return INSTANCE.completedScripts;
    }
}
