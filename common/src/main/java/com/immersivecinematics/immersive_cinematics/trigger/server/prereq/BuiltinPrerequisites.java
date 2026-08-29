package com.immersivecinematics.immersive_cinematics.trigger.server.prereq;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;

/**
 * 内置前置条件类型。
 * <p>
 * 由 {@code ImmersiveCinematics.init()} 调用 {@link #registerAll()} 注册。
 */
public final class BuiltinPrerequisites {

    private BuiltinPrerequisites() {}

    public static void registerAll() {
        // 脚本“播放过” = 收到开始播放信号 && 收到结束播放信号（任何退出原因都算）
        PrerequisiteRegistry.register("script_played", (player, data) -> {
            String script = getScript(data);
            return script != null && TriggerStateStore.INSTANCE.hasPlayed(player.getUUID(), script);
        });

        // 只要求开始播放
        PrerequisiteRegistry.register("script_started", (player, data) -> {
            String script = getScript(data);
            return script != null && TriggerStateStore.INSTANCE.isScriptStarted(player.getUUID(), script);
        });

        // 只要求结束播放（历史语义，保留给需要“只看结束”的作者）
        PrerequisiteRegistry.register("script_completed", (player, data) -> {
            String script = getScript(data);
            return script != null && TriggerStateStore.INSTANCE.isScriptCompleted(player.getUUID(), script);
        });
    }

    private static String getScript(JsonObject data) {
        if (data == null || !data.has("script") || !data.get("script").isJsonPrimitive()) return null;
        return data.get("script").getAsString();
    }
}
