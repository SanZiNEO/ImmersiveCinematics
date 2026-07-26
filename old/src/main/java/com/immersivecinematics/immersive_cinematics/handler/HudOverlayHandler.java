package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
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
            new ResourceLocation(ImmersiveCinematics.MODID, CinematicOverlay.OVERLAY_ID);

    private static final ResourceLocation CHAT_PANEL_ID = new ResourceLocation("minecraft", "chat_panel");
    private static final ResourceLocation SCOREBOARD_ID = new ResourceLocation("minecraft", "scoreboard");
    private static final ResourceLocation ACTION_BAR_ID = new ResourceLocation("minecraft", "action_bar");
    private static final ResourceLocation PLAYER_LIST_ID = new ResourceLocation("minecraft", "player_list");
    private static final ResourceLocation TITLE_TEXT_ID = new ResourceLocation("minecraft", "title_text");
    private static final ResourceLocation SUBTITLES_ID = new ResourceLocation("minecraft", "subtitles");
    private static final ResourceLocation HOTBAR_ID = new ResourceLocation("minecraft", "hotbar");
    private static final ResourceLocation CROSSHAIR_ID = new ResourceLocation("minecraft", "crosshair");

    private static final Set<ResourceLocation> BASE_ALLOWED = Set.of(CINEMATIC_OVERLAY_ID);

    private static final Map<ResourceLocation, Supplier<Boolean>> OPTIONAL_ALLOWED = Map.of(
            CHAT_PANEL_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideChat();
                return v == null ? null : !v;
            },
            SCOREBOARD_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideScoreboard();
                return v == null ? null : !v;
            },
            ACTION_BAR_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideActionBar();
                return v == null ? null : !v;
            },
            PLAYER_LIST_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideScoreboard();
                return v == null ? null : !v;
            },
            TITLE_TEXT_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideTitle();
                return v == null ? null : !v;
            },
            SUBTITLES_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideSubtitles();
                return v == null ? null : !v;
            },
            HOTBAR_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideHotbar();
                return v == null ? null : !v;
            },
            CROSSHAIR_ID, () -> {
                Boolean v = CinematicController.INSTANCE.isHideCrosshair();
                return v == null ? null : !v;
            }
    );

    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!CameraManager.INSTANCE.isActive()) return;

        CinematicController ctrl = CinematicController.INSTANCE;
        ResourceLocation overlayId = event.getOverlay().id();

        if (BASE_ALLOWED.contains(overlayId)) return;

        Supplier<Boolean> optional = OPTIONAL_ALLOWED.get(overlayId);
        if (optional != null) {
            Boolean allowed = optional.get();
            if (allowed == null) {
                if (ctrl.isHideHud()) {
                    event.setCanceled(true);
                }
            } else if (!allowed) {
                event.setCanceled(true);
            }
            return;
        }

        if (ctrl.isHideHud()) {
            event.setCanceled(true);
        }
    }
}
