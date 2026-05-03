package com.immersivecinematics.immersive_cinematics;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.command.CinematicCommand;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.handler.HudOverlayHandler;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.store.TriggerStateStore;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.Set;

@Mod(ImmersiveCinematics.MODID)
public class ImmersiveCinematics {

    public static final String MODID = "immersive_cinematics";

    public ImmersiveCinematics() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(CinematicOverlay::onRegisterGuiOverlays);
        modEventBus.addListener(CinematicKeyBindings::register);
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onClientSetup);

        MinecraftForge.EVENT_BUS.register(ClientTickEvents.class);
        MinecraftForge.EVENT_BUS.register(ClientHudEvents.class);
        MinecraftForge.EVENT_BUS.register(ClientCameraEvents.class);
        MinecraftForge.EVENT_BUS.register(ServerForgeEvents.class);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        NetworkHandler.register();
        registerTriggerTypes();
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new com.immersivecinematics.immersive_cinematics.client.ConfigScreen(parent)));
    }

    private void registerTriggerTypes() {
        TriggerRegistry.register(new TriggerType("location", ListenStrategy.POLLING, 20,
                Evaluators::evaluateLocation, Set.of()));
        TriggerRegistry.register(new TriggerType("advancement", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateAdvancement, Set.of(AdvancementEvent.AdvancementEarnEvent.class)));
        TriggerRegistry.register(new TriggerType("biome", ListenStrategy.POLLING, 40,
                Evaluators::evaluateBiome, Set.of()));
        TriggerRegistry.register(new TriggerType("entity_kill", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateEntityKill, Set.of(LivingDeathEvent.class)));
        TriggerRegistry.register(new TriggerType("interact", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateInteract, Set.of(
                        PlayerInteractEvent.RightClickBlock.class,
                        PlayerInteractEvent.LeftClickBlock.class,
                        PlayerInteractEvent.EntityInteract.class)));
        TriggerRegistry.register(new TriggerType("dimension_change", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateDimensionChange, Set.of(PlayerEvent.PlayerChangedDimensionEvent.class)));
        TriggerRegistry.register(new TriggerType("dimension", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateDimension, Set.of(PlayerEvent.PlayerChangedDimensionEvent.class)));
        TriggerRegistry.register(new TriggerType("login", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateLogin, Set.of(PlayerEvent.PlayerLoggedInEvent.class)));
        TriggerRegistry.register(new TriggerType("inventory", ListenStrategy.POLLING, 20,
                Evaluators::evaluateInventory, Set.of()));
        TriggerRegistry.register(new TriggerType("item_craft", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateItemCraft, Set.of(PlayerEvent.ItemCraftedEvent.class)));
        TriggerRegistry.register(new TriggerType("custom", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateCustom, Set.of()));
        TriggerRegistry.register(new TriggerType("command", ListenStrategy.EVENT_DRIVEN, 0,
                Evaluators::evaluateCommand, Set.of()));
        TriggerRegistry.register(new TriggerType("structure", ListenStrategy.POLLING, 20,
                Evaluators::evaluateStructure, Set.of()));
        TriggerRegistry.register(new TriggerType("gamestage", ListenStrategy.POLLING, 20,
                Evaluators::evaluateGamestage, Set.of()));
    }

    // ===== Server-side Forge Event Handlers =====

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ServerForgeEvents {

        @SubscribeEvent
        public static void onServerStarting(ServerStartedEvent event) {
            ScriptManager.INSTANCE.loadAll(event.getServer());
            TriggerStateStore.INSTANCE.initialize(event.getServer());
            TriggerEngine.INSTANCE.initialize();
            ScriptManager.INSTANCE.registerAllTriggers();
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            TriggerStateStore.INSTANCE.saveAll();
        }

        @SubscribeEvent
        public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            TriggerStateStore.INSTANCE.loadForPlayer(player.getUUID());
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            TriggerStateStore.INSTANCE.unloadForPlayer(player.getUUID());
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            TriggerEngine.INSTANCE.onServerTick(server);
            ScriptEventManager.INSTANCE.onServerTick(server);
        }

        @SubscribeEvent
        public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onLivingDeath(LivingDeathEvent event) {
            if (event.getSource().getEntity() instanceof ServerPlayer player) {
                Evaluators.KillTracker.record(player, event.getEntity().getType());
                TriggerEngine.INSTANCE.onGameEvent(event, player);
            }
        }

        @SubscribeEvent
        public static void onPlayerInteractBlock(PlayerInteractEvent.RightClickBlock event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            Evaluators.InteractTracker.recordBlock(player.getUUID(), event.getLevel().getBlockState(event.getPos()));
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onPlayerLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            Evaluators.InteractTracker.recordBlock(player.getUUID(), event.getLevel().getBlockState(event.getPos()));
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onPlayerInteractEntity(PlayerInteractEvent.EntityInteract event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            Evaluators.InteractTracker.recordEntity(player.getUUID(), event.getTarget().getType());
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            Evaluators.CraftTracker.record(player, event.getCrafting());
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            TriggerEngine.INSTANCE.onGameEvent(event, player);
        }

        @SubscribeEvent
        public static void onWorldSave(PlayerEvent.SaveToFile event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                TriggerStateStore.INSTANCE.saveIfChanged(player.getUUID());
            }
        }
    }

    // ===== Client Event Handlers (existing) =====

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientTickEvents {

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                CameraManager.INSTANCE.tick();
                CinematicKeyBindings.onClientTick();
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientHudEvents {

        @SubscribeEvent
        public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
            HudOverlayHandler.onRenderGuiOverlayPre(event);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class CommandEvents {

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CinematicCommand.register(event.getDispatcher());
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientCameraEvents {

        @SubscribeEvent
        public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            CameraManager mgr = CameraManager.INSTANCE;
            if (mgr.isActive()) {
                float roll = mgr.getProperties().getRoll();
                event.setRoll(roll);
            }
        }
    }
}
