package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.Immersive_cinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class HudOverlayHandler {

    private static final Set<ResourceLocation> BASE_ALLOWED = Set.of(
            new ResourceLocation(Immersive_cinematics.MODID, CinematicOverlay.OVERLAY_ID)
    );

    private static final Map<ResourceLocation, Supplier<Boolean>> OPTIONAL_ALLOWED = Map.of(
            VanillaGuiOverlay.CHAT.id(),
            () -> !CinematicController.INSTANCE.isBlockChat(),
            VanillaGuiOverlay.PLAYER_LIST.id(),
            () -> !CinematicController.INSTANCE.isBlockScoreboard(),
            VanillaGuiOverlay.ACTION_BAR.id(),
            () -> !CinematicController.INSTANCE.isBlockActionBar()
    );

    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!CameraManager.INSTANCE.isActive()) return;

        CinematicController ctrl = CinematicController.INSTANCE;
        ResourceLocation overlayId = event.getOverlay().id();

        if (BASE_ALLOWED.contains(overlayId)) return;

        if (!ctrl.isHideHud()) return;

        Supplier<Boolean> allowed = OPTIONAL_ALLOWED.get(overlayId);
        if (allowed != null && allowed.get()) return;

        event.setCanceled(true);
    }
}
