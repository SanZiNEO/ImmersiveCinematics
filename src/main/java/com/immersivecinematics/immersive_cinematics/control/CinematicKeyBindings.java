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
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_C,
        "key.categories.immersive_cinematics"
    );

    private static final long SKIP_HOLD_THRESHOLD_MS = 3000;
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

        // 跳过键强制绑定到 C 键，使用原始 GLFW 状态检测
        // 不依赖 KeyMapping.getKey()（Minecraft 会从 options.txt 覆盖默认值导致旧绑定残留）
        long window = Minecraft.getInstance().getWindow().getWindow();
        boolean skipDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_C);

        if (skipDown) {
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

        // Ctrl+P 强制退出 — 同样使用原始 GLFW 状态
        boolean ctrlDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean pDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_P);
        if (ctrlDown && pDown) {
            mgr.requestExit(ExitReason.FORCE_QUIT);
        }
    }
}
