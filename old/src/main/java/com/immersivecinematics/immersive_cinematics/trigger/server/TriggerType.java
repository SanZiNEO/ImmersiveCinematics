package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import java.util.Set;
import java.util.function.BiPredicate;

public class TriggerType {

    private final String id;
    private final ListenStrategy strategy;
    private final int pollInterval;
    private final BiPredicate<ServerPlayer, JsonObject> evaluator;
    private final Set<Class<? extends Event>> listenedEvents;

    public TriggerType(String id, ListenStrategy strategy, int pollInterval,
                       BiPredicate<ServerPlayer, JsonObject> evaluator,
                       Set<Class<? extends Event>> listenedEvents) {
        this.id = id;
        this.strategy = strategy;
        this.pollInterval = pollInterval;
        this.evaluator = evaluator;
        this.listenedEvents = listenedEvents;
    }

    public String getId() { return id; }
    public ListenStrategy getStrategy() { return strategy; }
    public int getPollInterval() { return pollInterval; }
    public BiPredicate<ServerPlayer, JsonObject> getEvaluator() { return evaluator; }
    public Set<Class<? extends Event>> getListenedEvents() { return listenedEvents; }

    public boolean evaluate(ServerPlayer player, JsonObject conditions) {
        return evaluator.test(player, conditions);
    }
}
