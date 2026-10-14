package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiPredicate;

public class TriggerType {

    /** 轮询间隔下限（tick）：JSON 字段 {@code poll_interval} / {@code trigger_poll_interval} 与
     *  Forge 配置 {@code triggerPollInterval_*} 的 {@code defineInRange(…, 1, 600)} 同口径。 */
    public static final int POLL_INTERVAL_MIN = 1;
    /** 轮询间隔上限（tick），见 {@link #POLL_INTERVAL_MIN} */
    public static final int POLL_INTERVAL_MAX = 600;

    private final String id;
    private final ListenStrategy strategy;
    private final int pollInterval;
    private final BiPredicate<ServerPlayer, JsonObject> evaluator;

    public TriggerType(String id, ListenStrategy strategy, int pollInterval,
                       BiPredicate<ServerPlayer, JsonObject> evaluator) {
        this.id = id;
        this.strategy = strategy;
        this.pollInterval = pollInterval;
        this.evaluator = evaluator;
    }

    public String getId() { return id; }
    public ListenStrategy getStrategy() { return strategy; }
    public int getPollInterval() { return pollInterval; }
    public BiPredicate<ServerPlayer, JsonObject> getEvaluator() { return evaluator; }

    /**
     * 解析一个注册项的生效轮询间隔（tick）：触发器级 {@code poll_interval} ?? 脚本级
     * {@code trigger_poll_interval} ?? 本类型注册默认值，并钳到 ≥ {@link #POLL_INTERVAL_MIN} 防除零；
     * 事件驱动类型不参与轮询，恒返回 0（调用方不得据此分桶）。
     *
     * @param triggerInterval 触发器级覆盖（null = 回落下一级）
     * @param scriptInterval  脚本级覆盖（null = 回落类型级默认）
     */
    public int resolvePollInterval(Integer triggerInterval, Integer scriptInterval) {
        if (strategy != ListenStrategy.POLLING) return 0;
        int interval = triggerInterval != null ? triggerInterval
                : scriptInterval != null ? scriptInterval : pollInterval;
        return Math.max(POLL_INTERVAL_MIN, interval);
    }

    public boolean evaluate(ServerPlayer player, JsonObject conditions) {
        return evaluator.test(player, conditions);
    }
}
