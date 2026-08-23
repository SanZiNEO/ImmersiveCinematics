package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import net.fabricmc.api.ModInitializer;

public final class ImmersiveCinematicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ImmersiveCinematics.init(FabricConfig.INSTANCE);
        FabricNetwork.init();
        // 假人引导器延迟到服务端启动时再设置，与 Forge 保持一致。
        FabricEvents.registerServer();
    }
}
