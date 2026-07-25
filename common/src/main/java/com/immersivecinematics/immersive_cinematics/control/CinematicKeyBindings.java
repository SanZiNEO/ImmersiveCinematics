package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class CinematicKeyBindings {

    public static final KeyMapping SKIP_KEY = new KeyMapping(
        "key.immersive_cinematics.skip",
        GLFW.GLFW_KEY_C,
        "key.categories.immersive_cinematics"
    );
    public static final KeyMapping EDITOR_KEY = ImmersiveCinematics.EDITOR_ENABLED
        ? new KeyMapping("key.immersive_cinematics.editor", GLFW.GLFW_KEY_F6,
            "key.categories.immersive_cinematics")
        : null;

    private static long skipKeyDownSince = 0;
    private static boolean skipTriggered = false;
    private static long editorClosedAt;
    private static final long EDITOR_REOPEN_COOLDOWN = 500; // ms

    /**
     * 每客户端 tick 调用一次。
     * <p>
     * 处理跳过键逻辑、强制退出键（Ctrl+P），以及编辑器按键（如果启用）。
     * 按键注册通过 {@link dev.architectury.registry.client.keymappings.KeyMappingRegistry}
     * 在 ClientEventHandler 中完成（Phase 7）。
     */
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

        if (ImmersiveCinematics.EDITOR_ENABLED && EDITOR_KEY != null) {
            while (EDITOR_KEY.consumeClick()) {
                if (!(mc.screen instanceof com.immersivecinematics.immersive_cinematics.editor.EditorScreen)
                        && System.currentTimeMillis() - editorClosedAt > EDITOR_REOPEN_COOLDOWN) {
                    mc.setScreen(new com.immersivecinematics.immersive_cinematics.editor.EditorScreen(
                            com.immersivecinematics.immersive_cinematics.client.EditorBridgeImpl.INSTANCE,
                            java.nio.file.Paths.get("immersive_cinematics", "scripts")));
                }
            }
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

    /** Cooldown reset when editor closes. */
    public static void notifyEditorClosed() {
        editorClosedAt = System.currentTimeMillis();
    }

    public static float getSkipHoldProgress() {
        if (skipTriggered) return 1f;
        if (skipKeyDownSince == 0) return 0f;
        return Math.min(1f, (float)(System.currentTimeMillis() - skipKeyDownSince) / Config.skipHoldThresholdMs);
    }
}
