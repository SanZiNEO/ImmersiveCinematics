package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ImmersiveCinematics.MOD_ID)
public final class ImmersiveCinematicsForge {
    public ImmersiveCinematicsForge() {
        // 注册 Forge 配置系统
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);

        // 网络桥接
        ForgeNetwork.init();

        // 服务端事件（假人引导器延迟到服务端启动时再设置，避免 Mohist 早期加载 Connection 失败）
        ForgeEvents.registerServer(FMLJavaModLoadingContext.get().getModEventBus(), MinecraftForge.EVENT_BUS);

        // 运行通用初始化
        ImmersiveCinematics.init(ForgeConfig.INSTANCE);
    }
}
