package com.immersivecinematics.immersive_cinematics.client;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public class ClientModEvents {

    public static void onClientSetup(FMLClientSetupEvent event) {
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new ConfigScreen(parent)));
    }
}
