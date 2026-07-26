package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiPredicate;

public class TriggerType {

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

    public boolean evaluate(ServerPlayer player, JsonObject conditions) {
        return evaluator.test(player, conditions);
    }
}
