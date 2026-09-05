package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.EditorBridgeImpl;
import com.immersivecinematics.immersive_cinematics.editor.EditorScreen;
import com.immersivecinematics.immersive_cinematics.webui.WebEditorServer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Paths;

public class CinematicKeyBindings {

    private static final String EDITOR_CATEGORY = "key.categories.immersive_cinematics";

    public static final KeyMapping SKIP_KEY = new KeyMapping(
        "key.immersive_cinematics.skip",
        GLFW.GLFW_KEY_C,
        EDITOR_CATEGORY
    );
    public static final KeyMapping EDITOR_KEY = (ImmersiveCinematics.EDITOR_ENABLED && Config.editorEnabled)
        ? new KeyMapping("key.immersive_cinematics.editor", GLFW.GLFW_KEY_F6,
            EDITOR_CATEGORY)
        : null;

    public static final KeyMapping EDITOR_PLAY_PAUSE    = new KeyMapping("key.immersive_cinematics.editor.play_pause",    GLFW.GLFW_KEY_SPACE,       EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_ADD_MARKER    = new KeyMapping("key.immersive_cinematics.editor.add_marker",    GLFW.GLFW_KEY_M,           EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_SET_LOOP_IN   = new KeyMapping("key.immersive_cinematics.editor.set_loop_in",   GLFW.GLFW_KEY_I,           EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_SET_LOOP_OUT  = new KeyMapping("key.immersive_cinematics.editor.set_loop_out",  GLFW.GLFW_KEY_O,           EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_PLAYHEAD_LEFT = new KeyMapping("key.immersive_cinematics.editor.playhead_left", GLFW.GLFW_KEY_LEFT,        EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_PLAYHEAD_RIGHT= new KeyMapping("key.immersive_cinematics.editor.playhead_right",GLFW.GLFW_KEY_RIGHT,       EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_NUDGE_UP      = new KeyMapping("key.immersive_cinematics.editor.nudge_up",      GLFW.GLFW_KEY_UP,          EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_NUDGE_DOWN    = new KeyMapping("key.immersive_cinematics.editor.nudge_down",    GLFW.GLFW_KEY_DOWN,        EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_HOME          = new KeyMapping("key.immersive_cinematics.editor.home",          GLFW.GLFW_KEY_HOME,        EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_END           = new KeyMapping("key.immersive_cinematics.editor.end",           GLFW.GLFW_KEY_END,         EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_PAGE_UP       = new KeyMapping("key.immersive_cinematics.editor.page_up",       GLFW.GLFW_KEY_PAGE_UP,     EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_PAGE_DOWN     = new KeyMapping("key.immersive_cinematics.editor.page_down",     GLFW.GLFW_KEY_PAGE_DOWN,   EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_CLIP_START    = new KeyMapping("key.immersive_cinematics.editor.clip_start",    GLFW.GLFW_KEY_LEFT_BRACKET, EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_CLIP_END      = new KeyMapping("key.immersive_cinematics.editor.clip_end",      GLFW.GLFW_KEY_RIGHT_BRACKET, EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_PLAY_CLIP     = new KeyMapping("key.immersive_cinematics.editor.play_clip",     GLFW.GLFW_KEY_ENTER,       EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_DELETE        = new KeyMapping("key.immersive_cinematics.editor.delete",        GLFW.GLFW_KEY_DELETE,      EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FRAME_ALL     = new KeyMapping("key.immersive_cinematics.editor.frame_all",     GLFW.GLFW_KEY_F,           EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT        = new KeyMapping("key.immersive_cinematics.editor.flight",        GLFW.GLFW_KEY_F7,          EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_WEBUI_OPEN    = new KeyMapping("key.immersive_cinematics.editor.webui_open",    GLFW.GLFW_KEY_F9,          EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_FOV_IN  = new KeyMapping("key.immersive_cinematics.editor.flight.fov_in",   GLFW.GLFW_KEY_EQUAL,       EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_FOV_OUT = new KeyMapping("key.immersive_cinematics.editor.flight.fov_out",  GLFW.GLFW_KEY_MINUS,       EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_ZOOM_IN = new KeyMapping("key.immersive_cinematics.editor.flight.zoom_in",  GLFW.GLFW_KEY_RIGHT_BRACKET, EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_ZOOM_OUT= new KeyMapping("key.immersive_cinematics.editor.flight.zoom_out", GLFW.GLFW_KEY_LEFT_BRACKET, EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_ROLL_LEFT = new KeyMapping("key.immersive_cinematics.editor.flight.roll_left", GLFW.GLFW_KEY_Q, EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_ROLL_RIGHT = new KeyMapping("key.immersive_cinematics.editor.flight.roll_right", GLFW.GLFW_KEY_E, EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_MODE    = new KeyMapping("key.immersive_cinematics.editor.flight.mode",     GLFW.GLFW_KEY_R,           EDITOR_CATEGORY);
    public static final KeyMapping EDITOR_FLIGHT_RESET_OPTICS = new KeyMapping("key.immersive_cinematics.editor.flight.reset_optics", GLFW.GLFW_KEY_C, EDITOR_CATEGORY);

    private static long skipKeyDownSince = 0;
    private static boolean skipTriggered = false;
    private static long editorClosedAt;
    private static final long EDITOR_REOPEN_COOLDOWN = 500;
    private static boolean webUiConnectedNotified = false;

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        CameraManager mgr = CameraManager.INSTANCE;
        if (!mgr.isActive()) {
            skipKeyDownSince = 0;
            skipTriggered = false;
        }

        if (mc.isPaused()) {
            skipKeyDownSince = 0;
        }

        // F6：旧 Java 编辑器（原版逻辑）
        if (ImmersiveCinematics.EDITOR_ENABLED && EDITOR_KEY != null) {
            while (EDITOR_KEY.consumeClick()) {
                if (!(mc.screen instanceof com.immersivecinematics.immersive_cinematics.editor.EditorScreen)
                        && System.currentTimeMillis() - editorClosedAt > EDITOR_REOPEN_COOLDOWN) {
                    mc.setScreen(new EditorScreen(EditorBridgeImpl.INSTANCE, Paths.get("immersive_cinematics", "scripts")));
                }
            }
        }

        // F9：WebUI 独立编辑器（打开预览屏，同时其 init 会启动本地服务）
        if (ImmersiveCinematics.EDITOR_ENABLED) {
            while (EDITOR_WEBUI_OPEN.consumeClick()) {
                if (!(mc.screen instanceof com.immersivecinematics.immersive_cinematics.webui.WebPreviewScreen)
                        && System.currentTimeMillis() - editorClosedAt > EDITOR_REOPEN_COOLDOWN) {
                    mc.setScreen(new com.immersivecinematics.immersive_cinematics.webui.WebPreviewScreen());
                }
            }
        }

        // 服务端运行时，检测到首次客户端连接后在聊天框提示一次
        if (WebEditorServer.INSTANCE.isRunning() && !webUiConnectedNotified && WebEditorServer.INSTANCE.hasClients()) {
            webUiConnectedNotified = true;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("message.immersive_cinematics.webui_connected"), false);
            }
        }
        if (!WebEditorServer.INSTANCE.hasClients()) {
            webUiConnectedNotified = false;
        }

        if (skipTriggered) {
        } else {
            boolean skipDown = SKIP_KEY.isDown();
            if (skipDown) {
                if (skipKeyDownSince == 0) {
                    skipKeyDownSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - skipKeyDownSince >= Config.skipHoldThresholdMs) {
                    boolean ok = mgr.requestExit(ExitReason.USER_SKIP);
                    if (ok) {
                        skipTriggered = true;
                        skipKeyDownSince = 0;
                    }
                }
            } else {
                skipKeyDownSince = 0;
            }
        }

        long window = mc.getWindow().getWindow();
        boolean ctrlDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean pDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_P);
        if (ctrlDown && pDown) {
            mgr.requestExit(ExitReason.FORCE_QUIT);
        }
    }

    public static void notifyEditorClosed() {
        editorClosedAt = System.currentTimeMillis();
    }

    public static float getSkipHoldProgress() {
        if (skipTriggered) return 1f;
        if (skipKeyDownSince == 0) return 0f;
        return Math.min(1f, (float)(System.currentTimeMillis() - skipKeyDownSince) / Config.skipHoldThresholdMs);
    }
}
