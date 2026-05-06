package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.EditorBridgeImpl;
import com.immersivecinematics.immersive_cinematics.editor.EditorScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.nio.file.Paths;

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
    private static boolean editorKeyWasDown = false;

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(SKIP_KEY);
        if (ImmersiveCinematics.EDITOR_ENABLED && EDITOR_KEY != null) {
            event.register(EDITOR_KEY);
        }
    }

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
            boolean editorDown = EDITOR_KEY.isDown();
            if (editorDown && !editorKeyWasDown && !(mc.screen instanceof EditorScreen)) {
                Path scriptsDir = Paths.get("cinematics");
                EditorScreen editor = new EditorScreen(EditorBridgeImpl.INSTANCE, scriptsDir);
                mc.setScreen(editor);
            }
            editorKeyWasDown = editorDown;
        }

        // Tick the editor's output dispatcher (throttled bridge calls)
        if (mc.screen instanceof EditorScreen editor) {
            editor.getEditorOutput().tick();
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

    public static float getSkipHoldProgress() {
        if (skipTriggered) return 1f;
        if (skipKeyDownSince == 0) return 0f;
        return Math.min(1f, (float)(System.currentTimeMillis() - skipKeyDownSince) / Config.skipHoldThresholdMs);
    }
}
