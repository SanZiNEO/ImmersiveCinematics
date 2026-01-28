package com.example.immersive_cinematics;

import com.example.immersive_cinematics.handler.KeyHandler;
import com.example.immersive_cinematics.handler.CommandHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(ImmersiveCinematics.MODID)
public class ImmersiveCinematics {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "immersive_cinematics";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Minecraft instance
    public static final Minecraft MC = Minecraft.getInstance();

    public ImmersiveCinematics() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
        // 注册时间轴处理器到事件总线
        MinecraftForge.EVENT_BUS.register(com.example.immersive_cinematics.director.TimelineProcessor.getInstance());
        // 注册世界事件检测器到事件总线（关键：这是监听器能正常工作的必要条件）
        MinecraftForge.EVENT_BUS.register(com.example.immersive_cinematics.trigger.WorldEventDetector.getInstance());
        LOGGER.info("WorldEventDetector registered to Forge EVENT_BUS");

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 强制输出高亮日志，确保能够在日志中看到
        LOGGER.error("!!! [IMMERSIVE_CINEMATICS] COMMON SETUP EVENT TRIGGERED !!!");
        System.out.println("!!! [IMMERSIVE_CINEMATICS] COMMON SETUP CALLED !!!");
        
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");
        
        // 初始化网络通信系统
        com.example.immersive_cinematics.network.NetworkHandler.register();
        LOGGER.info("Network communication system initialized");
        
        // 初始化镜头脚本存储系统
        LOGGER.info("Initializing camera script storage system...");
        com.example.immersive_cinematics.director.CameraScriptStorage.getInstance().initialize();
        LOGGER.info("Camera script storage system initialized");
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");

        // 注册自定义指令
        CommandHandler.registerCommands(event.getServer().getCommands().getDispatcher());
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

            // 初始化 KeyHandler
            KeyHandler.getInstance();
        }
    }
}
