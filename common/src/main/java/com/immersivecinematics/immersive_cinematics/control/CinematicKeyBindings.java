package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.webui.WebEditorServer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端键位注册与每 tick 输入轮询（跳过键长按、F9 编辑器、Ctrl+P 强制退出）。
 *
 * 跳过键要按住累计到 {@code Config.skipHoldThresholdMs} 才请求退出，且一次会话只触发一次
 * （skipTriggered 消费，避免长按重复请求）；F9 重开有 {@code EDITOR_REOPEN_COOLDOWN} 冷却，
 * 防止预览屏关闭瞬间的残留按键立刻重开。
 */
public class CinematicKeyBindings {

    private static final String EDITOR_CATEGORY = "key.categories.immersive_cinematics";

    /** 跳过键（默认 C）：长按触发，非单击。 */
    public static final KeyMapping SKIP_KEY = new KeyMapping(
        "key.immersive_cinematics.skip",
        GLFW.GLFW_KEY_C,
        EDITOR_CATEGORY
    );
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

    /** 每 tick 轮询输入；无世界时直接返回。 */
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

        // F9：WebUI 独立编辑器（打开预览屏，同时其 init 会启动本地服务）
        while (EDITOR_WEBUI_OPEN.consumeClick()) {
            if (!(mc.screen instanceof com.immersivecinematics.immersive_cinematics.webui.WebPreviewScreen)
                    && System.currentTimeMillis() - editorClosedAt > EDITOR_REOPEN_COOLDOWN) {
                mc.setScreen(new com.immersivecinematics.immersive_cinematics.webui.WebPreviewScreen());
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

    /** 预览屏关闭时调用，记录时间戳以启动 F9 重开冷却。 */
    public static void notifyEditorClosed() {
        editorClosedAt = System.currentTimeMillis();
    }

    /** 跳过键长按进度 [0,1]：已达阈值或已触发为 1，未按下为 0。 */
    public static float getSkipHoldProgress() {
        if (skipTriggered) return 1f;
        if (skipKeyDownSince == 0) return 0f;
        return Math.min(1f, (float)(System.currentTimeMillis() - skipKeyDownSince) / Config.skipHoldThresholdMs);
    }
}
