package com.immersivecinematics.immersive_cinematics.fabric.client;

import net.fabricmc.api.ClientModInitializer;

public final class ImmersiveCinematicsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricClientEvents.register();
    }
}
