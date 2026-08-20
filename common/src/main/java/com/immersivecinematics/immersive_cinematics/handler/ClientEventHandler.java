package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.control.SkipHudRenderer;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 客户端事件处理（0.3.5 第7轮去 Arch）。
 * <p>
 * 只保留纯逻辑；平台（Fabric/Forge）负责把原生事件转发到这里。
 */
public final class ClientEventHandler {

    @FunctionalInterface
    public interface KeyMappingRegistrar {
        void register(KeyMapping keyMapping);
    }

    private ClientEventHandler() {}

    public static void onClientInit() {
        com.immersivecinematics.immersive_cinematics.util.ResourcePath.ensureDir();
        try {
            java.nio.file.Files.createDirectories(
                    Minecraft.getInstance().gameDirectory.toPath()
                            .resolve("immersive_cinematics").resolve("scripts"));
        } catch (Exception ignored) {
            // 目录已存在/权限受限即跳过（ResourcePath.ensureDir 已处理 resource；scripts 由编辑器按需创建）
        }
    }

    public static void registerKeyMappings(KeyMappingRegistrar registrar) {
        registrar.register(CinematicKeyBindings.SKIP_KEY);
        if (ImmersiveCinematics.EDITOR_ENABLED && CinematicKeyBindings.EDITOR_KEY != null) {
            registrar.register(CinematicKeyBindings.EDITOR_KEY);
            registrar.register(CinematicKeyBindings.EDITOR_PLAY_PAUSE);
            registrar.register(CinematicKeyBindings.EDITOR_ADD_MARKER);
            registrar.register(CinematicKeyBindings.EDITOR_SET_LOOP_IN);
            registrar.register(CinematicKeyBindings.EDITOR_SET_LOOP_OUT);
            registrar.register(CinematicKeyBindings.EDITOR_PLAYHEAD_LEFT);
            registrar.register(CinematicKeyBindings.EDITOR_PLAYHEAD_RIGHT);
            registrar.register(CinematicKeyBindings.EDITOR_NUDGE_UP);
            registrar.register(CinematicKeyBindings.EDITOR_NUDGE_DOWN);
            registrar.register(CinematicKeyBindings.EDITOR_HOME);
            registrar.register(CinematicKeyBindings.EDITOR_END);
            registrar.register(CinematicKeyBindings.EDITOR_PAGE_UP);
            registrar.register(CinematicKeyBindings.EDITOR_PAGE_DOWN);
            registrar.register(CinematicKeyBindings.EDITOR_CLIP_START);
            registrar.register(CinematicKeyBindings.EDITOR_CLIP_END);
            registrar.register(CinematicKeyBindings.EDITOR_PLAY_CLIP);
            registrar.register(CinematicKeyBindings.EDITOR_DELETE);
            registrar.register(CinematicKeyBindings.EDITOR_FRAME_ALL);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_FOV_IN);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_FOV_OUT);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_IN);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_OUT);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_LEFT);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_RIGHT);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_MODE);
            registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_RESET_OPTICS);
        }
    }

    public static void onClientTick(Minecraft mc) {
        CameraManager.INSTANCE.tick();
        CinematicKeyBindings.onClientTick();
        com.immersivecinematics.immersive_cinematics.trigger.client.PreloadRequester.INSTANCE.tick(mc);
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.tick();
        if (mc.level == null && CameraManager.INSTANCE.isActive()) {
            CameraManager.INSTANCE.emergencyStop();
        }
    }

    public static void onRenderHud(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        CinematicOverlay.render(graphics,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight());
        SkipHudRenderer.render(graphics);
    }
}
