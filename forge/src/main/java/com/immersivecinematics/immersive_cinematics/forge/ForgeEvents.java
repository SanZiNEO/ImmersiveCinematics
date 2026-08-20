package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Forge 事件注册（0.3.5 第7轮去 Arch）。
 */
public final class ForgeEvents {

    private ForgeEvents() {}

    public static void registerServer(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.addListener(ForgeEvents::onServerStarted);
        forgeBus.addListener(ForgeEvents::onServerStopping);
        forgeBus.addListener(ForgeEvents::onPlayerJoin);
        forgeBus.addListener(ForgeEvents::onPlayerQuit);
        forgeBus.addListener(ForgeEvents::onServerTick);
        forgeBus.addListener(ForgeEvents::onRegisterCommands);
        forgeBus.addListener(ForgeEvents::onAdvancement);
        forgeBus.addListener(ForgeEvents::onLivingDeath);
        forgeBus.addListener(ForgeEvents::onRightClickBlock);
        forgeBus.addListener(ForgeEvents::onLeftClickBlock);
        forgeBus.addListener(ForgeEvents::onInteractEntity);
        forgeBus.addListener(ForgeEvents::onRightClickItem);
        forgeBus.addListener(ForgeEvents::onItemCrafted);
        forgeBus.addListener(ForgeEvents::onItemPickup);
        forgeBus.addListener(ForgeEvents::onItemToss);
        forgeBus.addListener(ForgeEvents::onChangedDimension);
        forgeBus.addListener(ForgeEvents::onEntityJoinLevel);
        forgeBus.addListener(ForgeEvents::onLevelSave);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerEventHandler.onServerStarted(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ServerEventHandler.onServerStopping(event.getServer());
    }

    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) ServerEventHandler.onPlayerJoin(sp);
    }

    private static void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) ServerEventHandler.onPlayerQuit(sp);
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ServerEventHandler.onServerTick(event.getServer());
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ServerEventHandler.onRegisterCommands(event.getDispatcher());
    }

    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerEventHandler.onPlayerAdvancement(sp, event.getAdvancement());
        }
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        ServerEventHandler.onLivingDeath(event.getEntity(), event.getSource());
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerEventHandler.onRightClickBlock(event.getEntity(), event.getHand(), event.getPos(), event.getFace());
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        ServerEventHandler.onLeftClickBlock(event.getEntity(), event.getHand(), event.getPos(), event.getFace());
    }

    private static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        ServerEventHandler.onInteractEntity(event.getEntity(), event.getTarget(), event.getHand());
    }

    private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerEventHandler.onRightClickItem(sp, event.getHand());
        }
    }

    private static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerEventHandler.onCraftItem(sp, event.getCrafting());
        }
    }

    private static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerEventHandler.onPickupItem(sp, event.getStack());
        }
    }

    private static void onItemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp) {
            ServerEventHandler.onDropItem(sp, event.getEntity().getItem());
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerEventHandler.onChangeDimension(sp, event.getFrom(), event.getTo());
        }
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        ServerEventHandler.onEntityAdded(event.getEntity());
    }

    private static void onLevelSave(LevelEvent.Save event) {
        ServerEventHandler.onLevelSave();
    }
}
