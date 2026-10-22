package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 输入路由器——决定按键/鼠标事件要路由到哪里。
 *
 * 两层设计：接收层是 Mixin，在 HEAD 捕获原始事件且永不 cancel；传输层是本接口，决定每个事件的目标。
 *
 * 可替换实现。默认实现根据 {@link CinematicController} 的 blockKeyboard/blockMouse 判断当前是否需要
 * 拦截，并对配置中的白名单键放行。
 */
public interface InputRouter {

    /** 键盘事件的目标：飞行取景与跳过键优先，其余看键盘屏蔽开关。 */
    InputTarget routeKeyboard(int key, int scanCode, int action, int modifiers);

    /** 鼠标按键事件的目标：飞行取景优先，其余看鼠标屏蔽开关。 */
    InputTarget routeMouseButton(int button, int action, int modifiers);

    /** 滚轮事件的目标：飞行取景优先，其余看鼠标屏蔽开关。 */
    InputTarget routeMouseScroll(double delta);

    /** 视角转动事件的目标：飞行取景优先，其余看鼠标屏蔽开关。 */
    InputTarget routeTurnPlayer();

    // ===== 默认实现 =====

    /** 默认实现：无状态，每次调用按当前相机 / 控制状态判定目标。 */
    static InputRouter createDefault() {
        return new InputRouter() {
            public InputTarget routeKeyboard(int key, int scanCode, int action, int modifiers) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return InputTarget.GAME;

                // 飞行取景：F7/Esc 放行给预览屏退出/取消，其余按键全部交给飞控
                if (FlightModeManager.INSTANCE.isActive()) {
                    if (CinematicKeyBindings.EDITOR_FLIGHT.matches(key, scanCode) || key == GLFW.GLFW_KEY_ESCAPE) {
                        return InputTarget.GAME;
                    }
                    return InputTarget.FLIGHT;
                }

                if (!CameraManager.INSTANCE.isActive()) return InputTarget.GAME;

                // 跳过键不走 blockKeyboard——即使用户关掉了键盘屏蔽也能长按跳过
                if (CinematicKeyBindings.SKIP_KEY.matches(key, scanCode)) {
                    return InputTarget.SELF;
                }

                CinematicController ctrl = CinematicController.INSTANCE;
                if (!ctrl.isBlockKeyboard()) return InputTarget.GAME;
                if (mc.isPaused() && CameraManager.INSTANCE.isAnyPauseWhenGamePaused()) return InputTarget.GAME;

                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                    return InputTarget.GAME;
                }
                return InputTarget.BLOCK;
            }

            @Override
            public InputTarget routeMouseButton(int button, int action, int modifiers) {
                if (FlightModeManager.INSTANCE.isActive()) return InputTarget.FLIGHT;
                return shouldBlockMouse() ? InputTarget.BLOCK : InputTarget.GAME;
            }

            @Override
            public InputTarget routeMouseScroll(double delta) {
                if (FlightModeManager.INSTANCE.isActive()) return InputTarget.FLIGHT;
                return shouldBlockMouse() ? InputTarget.BLOCK : InputTarget.GAME;
            }

            @Override
            public InputTarget routeTurnPlayer() {
                if (FlightModeManager.INSTANCE.isActive()) return InputTarget.FLIGHT;
                return shouldBlockMouse() ? InputTarget.BLOCK : InputTarget.GAME;
            }

            private boolean shouldBlockMouse() {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return false;
                if (!CameraManager.INSTANCE.isActive()) return false;
                if (!CinematicController.INSTANCE.isBlockMouse()) return false;
                if (mc.isPaused() && CameraManager.INSTANCE.isAnyPauseWhenGamePaused()) return false;
                return true;
            }
        };
    }
}
