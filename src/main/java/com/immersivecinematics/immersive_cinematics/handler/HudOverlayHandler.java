package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.Immersive_cinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class HudOverlayHandler {

    private static final ResourceLocation CINEMATIC_OVERLAY_ID =
            new ResourceLocation(Immersive_cinematics.MODID, CinematicOverlay.OVERLAY_ID);

    private static final ResourceLocation CHAT_ID = new ResourceLocation("minecraft", "chat");
    private static final ResourceLocation PLAYER_LIST_ID = new ResourceLocation("minecraft", "player_list");
    private static final ResourceLocation ACTION_BAR_ID = new ResourceLocation("minecraft", "action_bar");

    private static final Set<ResourceLocation> BASE_ALLOWED = Set.of(CINEMATIC_OVERLAY_ID);

    private static final Map<ResourceLocation, Supplier<Boolean>> OPTIONAL_ALLOWED = Map.of(
            CHAT_ID, () -> !CinematicController.INSTANCE.isBlockChat(),
            PLAYER_LIST_ID, () -> !CinematicController.INSTANCE.isBlockScoreboard(),
            ACTION_BAR_ID, () -> !CinematicController.INSTANCE.isBlockActionBar()
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
