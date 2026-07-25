package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;

/**
 * 输入路由器——决定按键/鼠标事件要路由到哪里。
 * <p>
 * 两层设计：
 * <ol>
 *   <li><b>接收层</b> — Mixin 在 HEAD 捕获原始事件，永不 cancel</li>
 *   <li><b>传输层</b> — 本接口决定每个事件的目标</li>
 * </ol>
 * <p>
 * 可替换实现。默认实现根据 {@link CinematicController} 的 blockKeyboard/blockMouse
 * 判断当前是否需要拦截，并对配置中的白名单键放行。
 */
public interface InputRouter {

    InputTarget routeKeyboard(int key, int scanCode, int action, int modifiers);

    InputTarget routeMouseButton(int button, int action, int modifiers);

    InputTarget routeMouseScroll(double delta);

    InputTarget routeTurnPlayer();

    // ===== 默认实现 =====

    static InputRouter createDefault() {
        return new InputRouter() {
            @Override
            public InputTarget routeKeyboard(int key, int scanCode, int action, int modifiers) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return InputTarget.GAME;
                if (!CameraManager.INSTANCE.isActive()) return InputTarget.GAME;
                CinematicController ctrl = CinematicController.INSTANCE;
                if (!ctrl.isBlockKeyboard()) return InputTarget.GAME;
                if (mc.isPaused() && ctrl.isPauseWhenGamePaused()) return InputTarget.GAME;

                if (CinematicKeyBindings.SKIP_KEY.matches(key, scanCode)) {
                    return InputTarget.SELF;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                    return InputTarget.GAME;
                }
                return InputTarget.BLOCK;
            }

            @Override
            public InputTarget routeMouseButton(int button, int action, int modifiers) {
                return shouldBlockMouse() ? InputTarget.BLOCK : InputTarget.GAME;
            }

            @Override
            public InputTarget routeMouseScroll(double delta) {
                return shouldBlockMouse() ? InputTarget.BLOCK : InputTarget.GAME;
            }

            @Override
            public InputTarget routeTurnPlayer() {
                return shouldBlockMouse() ? InputTarget.BLOCK : InputTarget.GAME;
            }

            private boolean shouldBlockMouse() {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return false;
                if (!CameraManager.INSTANCE.isActive()) return false;
                if (!CinematicController.INSTANCE.isBlockMouse()) return false;
                if (mc.isPaused() && CinematicController.INSTANCE.isPauseWhenGamePaused()) return false;
                return true;
            }
        };
    }
}
