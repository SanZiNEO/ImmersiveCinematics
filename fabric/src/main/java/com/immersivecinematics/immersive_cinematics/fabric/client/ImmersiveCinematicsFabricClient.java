package com.immersivecinematics.immersive_cinematics.fabric.client;

import com.immersivecinematics.immersive_cinematics.fabric.FabricEvents;
import net.fabricmc.api.ClientModInitializer;

public final class ImmersiveCinematicsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricEvents.registerClient();
    }
}
