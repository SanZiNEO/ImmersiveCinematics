package com.immersivecinematics.immersive_cinematics;

import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import com.immersivecinematics.immersive_cinematics.trigger.server.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;

public final class ImmersiveCinematics {
    public static final String MOD_ID = "immersive_cinematics";

    public static final boolean EDITOR_ENABLED = true;

    public static void init(Config.ConfigProvider configProvider) {
        Config.init(configProvider);
        NetworkHandler.init();
        registerTriggerTypes();
        ServerEventHandler.register();
        EnvExecutor.runInEnv(Env.CLIENT, () -> ClientEventHandler::register);
    }

    private static void registerTriggerTypes() {
        TriggerRegistry.register(new TriggerType("location", ListenStrategy.POLLING, Config.triggerPollIntervalLocation, Evaluators::evaluateLocation));
        TriggerRegistry.register(new TriggerType("advancement", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateAdvancement));
        TriggerRegistry.register(new TriggerType("biome", ListenStrategy.POLLING, Config.triggerPollIntervalBiome, Evaluators::evaluateBiome));
        TriggerRegistry.register(new TriggerType("entity_kill", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateEntityKill));
        TriggerRegistry.register(new TriggerType("entity_interact", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateInteract));
        TriggerRegistry.register(new TriggerType("dimension_change", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateDimensionChange));
        TriggerRegistry.register(new TriggerType("login", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateLogin));
        TriggerRegistry.register(new TriggerType("inventory", ListenStrategy.POLLING, Config.triggerPollIntervalInventory, Evaluators::evaluateInventory));
        TriggerRegistry.register(new TriggerType("item_craft", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateItemCraft));
        TriggerRegistry.register(new TriggerType("custom", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateCustom));
        TriggerRegistry.register(new TriggerType("structure", ListenStrategy.POLLING, Config.triggerPollIntervalStructure, Evaluators::evaluateStructure));
        TriggerRegistry.register(new TriggerType("gamestage", ListenStrategy.POLLING, Config.triggerPollIntervalGamestage, Evaluators::evaluateGamestage));
        TriggerRegistry.register(new TriggerType("item_use", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateItemUse));
        TriggerRegistry.register(new TriggerType("item_consume", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateItemConsume));
        TriggerRegistry.register(new TriggerType("block_interact", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateBlockInteract));
        TriggerRegistry.register(new TriggerType("item_on_interact", ListenStrategy.EVENT_DRIVEN, 0, Evaluators::evaluateItemOnInteract));
    }
}
