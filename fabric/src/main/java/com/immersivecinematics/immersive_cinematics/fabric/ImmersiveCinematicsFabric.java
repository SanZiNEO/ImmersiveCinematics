package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraMobManager;
import net.fabricmc.api.ModInitializer;

public final class ImmersiveCinematicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ImmersiveCinematics.init(FabricConfig.INSTANCE);
        FabricNetwork.init();
        CameraMobManager.setBootstrapper(new FabricFakePlayerBootstrapper());
        FabricEvents.registerServer();
    }
}
