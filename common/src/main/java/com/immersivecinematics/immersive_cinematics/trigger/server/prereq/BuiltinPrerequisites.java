package com.immersivecinematics.immersive_cinematics.trigger.server.prereq;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactQueries;

/**
 * 内置前置条件类型：由 {@code ImmersiveCinematics.init()} 调用 {@link #registerAll()} 注册。
 *
 * <p>判定源是服务端事实账本（{@link FactQueries}）：{@code PLAYBACK_STARTED} / {@code PLAYBACK_FINISHED}
 * 两类事实按「玩家 + 脚本」查询，与播放实例、退出原因无关——结束过即满足，
 * 语义与旧布尔集合的「播放过 / 开始过 / 结束过」逐位一致。
 */
public final class BuiltinPrerequisites {

    private BuiltinPrerequisites() {}

    public static void registerAll() {
        // 脚本“播放过” = 有开始播放事实 && 有结束播放事实（任何退出原因都算）
        PrerequisiteRegistry.register("script_played", (player, data) -> {
            String script = getScript(data);
            return script != null && FactQueries.scriptPlayed(player.getUUID(), script);
        });

        // 只要求有开始播放事实
        PrerequisiteRegistry.register("script_started", (player, data) -> {
            String script = getScript(data);
            return script != null && FactQueries.scriptStarted(player.getUUID(), script);
        });

        // 只要求有结束播放事实：沿用“结束信号即满足”的语义，不校验是否开始过（保留给“只看结束”的作者）
        PrerequisiteRegistry.register("script_completed", (player, data) -> {
            String script = getScript(data);
            return script != null && FactQueries.scriptFinished(player.getUUID(), script);
        });
    }

    private static String getScript(JsonObject data) {
        if (data == null || !data.has("script") || !data.get("script").isJsonPrimitive()) return null;
        return data.get("script").getAsString();
    }
}
