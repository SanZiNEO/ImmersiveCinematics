package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.control.FlightController;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * WebUI 编辑器预览屏幕。
 *
 * <p>游戏端只做三件事：
 * 1. 画面传输（把游戏画面编码后通过 WebSocket 发给前端）
 * 2. 播放控制（接收前端的 play/pause/seek/stop/pushScript 指令）
 * 3. 飞控模式（前端发送 enter_flight_mode 指令后，玩家在游戏中用 WASD 飞行编辑关键帧）
 *
 * <p>所有编辑器按键绑定由前端自己处理，游戏端不转发。</p>
 */
public class WebPreviewScreen extends Screen {

    public WebPreviewScreen() {
        super(Component.literal("Web Preview"));
    }

    @Override
    protected void init() {
        WebEditorServer.INSTANCE.start();
        if (minecraft.player != null) {
            if (WebEditorServer.INSTANCE.hasClients()) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.immersive_cinematics.webui_connected"), false);
            } else {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.immersive_cinematics.webui_not_running"), false);
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        minecraft.renderBuffers().bufferSource().endBatch();
        WebFrameCapture.capture(minecraft);
        WebFrameStreamer.onFrame();

        // 飞控模式下每帧把当前相机参数发给前端（实时预览）
        if (FlightController.INSTANCE.isActive()) {
            broadcastFlightState();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // F6 / ESC 关闭编辑器
        if (CinematicKeyBindings.EDITOR_KEY != null && CinematicKeyBindings.EDITOR_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // 飞控模式下 ESC = 取消并恢复初始值
            if (FlightController.INSTANCE.isActive()) {
                FlightController.INSTANCE.cancel();
                broadcastFlightExit(true);
                return true;
            }
            onClose();
            return true;
        }

        // F7 切换飞控模式（进入/退出）
        if (CinematicKeyBindings.EDITOR_FLIGHT.matches(keyCode, scanCode)) {
            if (FlightController.INSTANCE.isActive()) {
                FlightController.INSTANCE.exit();
                broadcastFlightExit(false);
            }
            // 进入飞控模式由前端指令触发（editor.enter_flight_mode），这里只处理退出
            return true;
        }

        // 飞控模式下把按键事件转给 FlightController
        if (FlightController.INSTANCE.isActive()) {
            FlightController.INSTANCE.onKeyEvent(keyCode, scanCode, 1);
            return true;
        }

        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (FlightController.INSTANCE.isActive()) {
            FlightController.INSTANCE.onKeyEvent(keyCode, scanCode, 0);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** 前端请求进入飞控模式（携带当前关键帧的初始参数） */
    public static void enterFlightMode(double x, double y, double z, float yaw, float pitch,
                                       float roll, float fov, float zoom, boolean absolute) {
        FlightController.INSTANCE.enter(new Vec3(x, y, z), yaw, pitch, roll, fov, zoom, absolute);
    }

    /** 广播飞控模式下的实时相机状态 */
    private void broadcastFlightState() {
        JsonObject data = new JsonObject();
        Vec3 pos = FlightController.INSTANCE.getPos();
        data.addProperty("x", pos.x);
        data.addProperty("y", pos.y);
        data.addProperty("z", pos.z);
        data.addProperty("yaw", FlightController.INSTANCE.getYaw());
        data.addProperty("pitch", FlightController.INSTANCE.getPitch());
        data.addProperty("roll", FlightController.INSTANCE.getRoll());
        data.addProperty("fov", FlightController.INSTANCE.getFov());
        data.addProperty("zoom", FlightController.INSTANCE.getZoom());
        broadcast("flight.state", data);
    }

    /** 广播飞控模式退出（携带最终参数） */
    private void broadcastFlightExit(boolean cancelled) {
        JsonObject data = new JsonObject();
        data.addProperty("cancelled", cancelled);
        if (!cancelled) {
            Vec3 pos = FlightController.INSTANCE.getPos();
            data.addProperty("x", pos.x);
            data.addProperty("y", pos.y);
            data.addProperty("z", pos.z);
            data.addProperty("yaw", FlightController.INSTANCE.getYaw());
            data.addProperty("pitch", FlightController.INSTANCE.getPitch());
            data.addProperty("roll", FlightController.INSTANCE.getRoll());
            data.addProperty("fov", FlightController.INSTANCE.getFov());
            data.addProperty("zoom", FlightController.INSTANCE.getZoom());
        }
        broadcast("flight.exit", data);
    }

    private void broadcast(String type, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        if (data != null) msg.add("data", data);
        WebEditorServer.INSTANCE.broadcastText(msg.toString());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) { return true; }
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) { return true; }
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return true; }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) { return true; }
    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (FlightController.INSTANCE.isActive()) {
            FlightController.INSTANCE.exit();
        }
        CameraManager.INSTANCE.exitPreview();
        WebFrameCapture.destroy();
        WebEditorServer.INSTANCE.stop();
        CinematicKeyBindings.notifyEditorClosed();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
