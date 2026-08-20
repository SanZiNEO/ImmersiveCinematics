package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import net.fabricmc.api.ModInitializer;

public final class ImmersiveCinematicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ImmersiveCinematics.init(FabricConfig.INSTANCE);
        FabricNetwork.init();
        FabricEvents.registerServer();
    }
}
