package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import net.fabricmc.api.ModInitializer;

public final class ImmersiveCinematicsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // 在模组加载就绪后立即运行

        // 运行通用初始化（传入 Fabric 配置实现）
        ImmersiveCinematics.init(FabricConfig.INSTANCE);
    }
}
