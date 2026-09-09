package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * 独立飞控模式管理器。
 *
 * <p>飞控核心会话与 {@link EditorScreen} 解耦，WebUI / 游戏内编辑器 / 键盘中转都统一走这里。
 * 以后移除游戏内 EditorScreen 后，WebUI 飞控仍可独立工作。
 *
 * <p>职责：
 * <ul>
 *   <li>进入飞控：暂停相机、启用直控、初始化 FlightController</li>
 *   <li>退出/取消：恢复直控、返回最终相机数据或恢复初始值</li>
 *   <li>每帧 tick、键盘事件转发、光学 reset</li>
 * </ul>
 */
public final class FlightModeManager {

    public static final FlightModeManager INSTANCE = new FlightModeManager();

    private FlightModeManager() {
    }

    /** 当前飞控会话的相机快照 */
    public static final class FlightState {
        public final Vec3 pos;
        public final float yaw;
        public final float pitch;
        public final float roll;
        public final float fov;
        public final float zoom;

        FlightState(Vec3 pos, float yaw, float pitch, float roll, float fov, float zoom) {
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.fov = fov;
            this.zoom = zoom;
        }
    }

    public boolean isActive() {
        return FlightController.INSTANCE.isActive();
    }

    /** 进入飞控：暂停相机并启用直控，然后初始化 FlightController。 */
    public void enter(Vec3 startPos, float yaw, float pitch,
                      float roll, float fov, float zoom, boolean absolute) {
        if (isActive()) return;
        CameraManager.INSTANCE.pause();
        CameraManager.INSTANCE.setPreviewDirectControl(true);
        FlightController.INSTANCE.enter(startPos, yaw, pitch, roll, fov, zoom, absolute);
        lockCursor();
    }

    /** 退出飞控：返回最终相机数据并关闭直控。未激活时返回 null。 */
    public FlightState exit() {
        if (!isActive()) return null;
        FlightState state = getState();
        FlightController.INSTANCE.exit();
        CameraManager.INSTANCE.setPreviewDirectControl(false);
        unlockCursor();
        return state;
    }

    /** 取消飞控：恢复进入前状态并关闭直控。 */
    public void cancel() {
        if (!isActive()) return;
        FlightController.INSTANCE.cancel();
        CameraManager.INSTANCE.setPreviewDirectControl(false);
        unlockCursor();
    }

    /** 每帧驱动飞控移动/光学。 */
    public void tick() {
        FlightController.INSTANCE.tick();
    }

    /** 键盘事件转发。 */
    public void onKeyEvent(int key, int scanCode, int action) {
        FlightController.INSTANCE.onKeyEvent(key, scanCode, action);
    }

    /** 鼠标移动事件转发。 */
    public void onMouseMove(double dx, double dy) {
        FlightController.INSTANCE.onMouseMove(dx, dy);
    }

    /** 光学重置：只恢复 FOV / Zoom / Roll，不恢复位置和朝向。 */
    public void resetOptics() {
        FlightController.INSTANCE.resetOptics();
    }

    private static void lockCursor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) {
            GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }

    private static void unlockCursor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) {
            GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    /** 是否按住慢速键（Ctrl）。 */
    public boolean isSlowDown() {
        return FlightController.INSTANCE.isSlowDown();
    }

    /** 当前坐标模式是否绝对模式。 */
    public boolean isModeAbsolute() {
        return FlightController.INSTANCE.isModeAbsolute();
    }

    /** 当前飞控相机状态。 */
    public FlightState getState() {
        Vec3 pos = FlightController.INSTANCE.getPos();
        return new FlightState(
                pos,
                FlightController.INSTANCE.getYaw(),
                FlightController.INSTANCE.getPitch(),
                FlightController.INSTANCE.getRoll(),
                FlightController.INSTANCE.getFov(),
                FlightController.INSTANCE.getZoom()
        );
    }
}
