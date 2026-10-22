package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiPredicate;

/**
 * 触发器类型定义：id + 监听策略 + 轮询间隔 + 求值器。
 * 求值器只读玩家 / 世界状态，必须在服务端主线程调用；{@code pollInterval} 是类型级默认值，
 * 可被脚本级 / 触发器级覆盖（见 {@link #resolvePollInterval}）。
 */
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

    /** 类型 id：JSON {@code type} 字段的取值，也是事件索引的键。 */
    public String getId() { return id; }
    /** 监听策略：事件驱动或轮询。 */
    public ListenStrategy getStrategy() { return strategy; }
    /** 类型级默认轮询间隔（tick）；事件驱动类型为 0。 */
    public int getPollInterval() { return pollInterval; }
    /** 条件求值器：{@code (玩家, conditions)} → 是否命中。 */
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

    /** 用本类型的求值器判定条件；异常由调用方兜底（{@code TriggerEngine} 只跳过该触发器）。 */
    public boolean evaluate(ServerPlayer player, JsonObject conditions) {
        return evaluator.test(player, conditions);
    }
}
