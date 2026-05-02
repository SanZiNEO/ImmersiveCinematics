package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class CinematicKeyBindings {

    public static final KeyMapping SKIP_KEY = new KeyMapping(
        "key.immersive_cinematics.skip",
        GLFW.GLFW_KEY_ESCAPE,
        "key.categories.immersive_cinematics"
    );

    private static final long SKIP_HOLD_THRESHOLD_MS = 500;
    private static long skipKeyDownSince = 0;

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(SKIP_KEY);
    }

    public static void onClientTick() {
        CameraManager mgr = CameraManager.INSTANCE;
        if (!mgr.isActive()) {
            skipKeyDownSince = 0;
            return;
        }

        if (SKIP_KEY.isDown()) {
            if (skipKeyDownSince == 0) {
                skipKeyDownSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - skipKeyDownSince >= SKIP_HOLD_THRESHOLD_MS) {
                boolean ok = mgr.requestExit(ExitReason.USER_SKIP);
                if (ok) {
                    skipKeyDownSince = 0;
                }
            }
        } else {
            skipKeyDownSince = 0;
        }

        long window = Minecraft.getInstance().getWindow().getWindow();
        boolean ctrlDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean pDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_P);
        if (ctrlDown && pDown) {
            mgr.requestExit(ExitReason.FORCE_QUIT);
        }
    }
}
