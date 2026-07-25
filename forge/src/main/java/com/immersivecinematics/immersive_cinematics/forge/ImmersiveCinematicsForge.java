package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.client.ConfigScreen;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ImmersiveCinematics.MOD_ID)
public final class ImmersiveCinematicsForge {
    public ImmersiveCinematicsForge() {
        // 提交事件总线，让 Architectury API 在正确时机注册内容
        EventBuses.registerModEventBus(ImmersiveCinematics.MOD_ID,
                FMLJavaModLoadingContext.get().getModEventBus());

        // 注册 Forge 配置系统
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);

        // 客户端设置（ConfigScreen 注册）
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);

        // 运行通用初始化
        ImmersiveCinematics.init(ForgeConfig.INSTANCE);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new ConfigScreen(parent)));
    }
}
