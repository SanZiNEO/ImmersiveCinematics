package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import java.util.UUID;

/**
 * 纯静态事实查询助手：前置条件与探针的共同入口。
 *
 * <p>只依赖 {@link FactLedger#INSTANCE} 与 {@link UUID}，不依赖 {@code ServerPlayer}，
 * 因此可在无客户端 / 无实体环境的探针里直接调用。
 */
public final class FactQueries {

    private FactQueries() {}

    public static boolean scriptStarted(UUID player, String script) {
        return FactLedger.INSTANCE.of(player).exists(FactType.PLAYBACK_STARTED, script, "");
    }

    public static boolean scriptFinished(UUID player, String script) {
        return FactLedger.INSTANCE.of(player).exists(FactType.PLAYBACK_FINISHED, script, "");
    }

    /** 开始且结束过（任何结果都算），对应旧 {@code hasPlayed}。 */
    public static boolean scriptPlayed(UUID player, String script) {
        return scriptStarted(player, script) && scriptFinished(player, script);
    }

    public static boolean triggerFired(UUID player, String script, String triggerId) {
        return FactLedger.INSTANCE.of(player).exists(FactType.TRIGGER_FIRED, script, triggerId);
    }

    /** 该脚本最近一次结束事实；无记录返回 {@code null}。 */
    public static Fact lastPlaybackFinished(UUID player, String script) {
        return FactLedger.INSTANCE.of(player).last(FactType.PLAYBACK_FINISHED, script, "");
    }
}
