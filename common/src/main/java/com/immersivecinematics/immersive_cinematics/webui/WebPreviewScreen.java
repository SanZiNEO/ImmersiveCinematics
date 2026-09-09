package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.control.FlightModeManager;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

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

    /** 当前打开的 WebPreviewScreen 实例，供静态飞控请求广播退出状态 */
    private static WebPreviewScreen activeScreen;

    /** 飞控状态广播节流：100ms 一次，避免每帧 JSON 刷屏导致 CPU 高负载 */
    private long lastFlightStateBroadcast;
    /** 播放状态广播节流：50ms 一次（约 20Hz），对齐旧 EditorOutput 节流 */
    private long lastPlaybackStateBroadcast;

    public WebPreviewScreen() {
        super(Component.literal("Web Preview"));
    }

    @Override
    protected void init() {
        activeScreen = this;
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

        // 核心双向通信：按 50ms 节流把真实播放器时间/播放状态推给外部编辑器。
        // 没有这个，前端只能收到画面，永远不知道脚本是否在播放、播到哪。
        long nowPs = System.currentTimeMillis();
        if (nowPs - lastPlaybackStateBroadcast >= 50) {
            lastPlaybackStateBroadcast = nowPs;
            WebEditorApi.pushPlaybackState();
        }

        // 飞控模式下：每帧驱动移动/光学，并按 100ms 节流发当前相机参数
        if (FlightModeManager.INSTANCE.isActive()) {
            FlightModeManager.INSTANCE.tick();
            long nowFlight = System.currentTimeMillis();
            if (nowFlight - lastFlightStateBroadcast >= 100) {
                lastFlightStateBroadcast = nowFlight;
                broadcastFlightState();
            }
            drawFlightHud(guiGraphics);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // F9 / ESC 关闭 WebUI 编辑器
        if (CinematicKeyBindings.EDITOR_WEBUI_OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // 飞控模式下 ESC = 取消并恢复初始值
            if (FlightModeManager.INSTANCE.isActive()) {
                FlightModeManager.INSTANCE.cancel();
                broadcastFlightExit(true);
                return true;
            }
            onClose();
            return true;
        }

        // F7 切换飞控模式（进入/退出）
        if (CinematicKeyBindings.EDITOR_FLIGHT.matches(keyCode, scanCode)) {
            if (FlightModeManager.INSTANCE.isActive()) {
                FlightModeManager.INSTANCE.exit();
                broadcastFlightExit(false);
            }
            // 进入飞控模式由前端指令触发（editor.enter_flight_mode），这里只处理退出
            return true;
        }

        // 飞控模式下把按键事件转给独立飞控模块
        if (FlightModeManager.INSTANCE.isActive()) {
            FlightModeManager.INSTANCE.onKeyEvent(keyCode, scanCode, 1);
            return true;
        }

        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (FlightModeManager.INSTANCE.isActive()) {
            FlightModeManager.INSTANCE.onKeyEvent(keyCode, scanCode, 0);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** 前端请求进入飞控模式：与 Java 编辑器一致，用当前实际相机作为初始状态 */
    public static void enterFlightMode(double x, double y, double z, float yaw, float pitch,
                                       float roll, float fov, float zoom, boolean absolute) {
        CameraManager cam = CameraManager.INSTANCE;
        Vec3 pos = cam.getPath().getPosition();
        FlightModeManager.INSTANCE.enter(
                pos,
                cam.getProperties().getYaw(),
                cam.getProperties().getPitch(),
                cam.getProperties().getRoll(),
                cam.getProperties().getFov(),
                cam.getProperties().getZoom(),
                absolute
        );
    }

    /** 前端请求退出/取消飞控，并向 WebUI 广播结果 */
    public static void requestFlightExit(boolean record) {
        if (activeScreen == null || !FlightModeManager.INSTANCE.isActive()) return;
        if (record) {
            FlightModeManager.INSTANCE.exit();
            activeScreen.broadcastFlightExit(false);
        } else {
            FlightModeManager.INSTANCE.cancel();
            activeScreen.broadcastFlightExit(true);
        }
    }

    /** WebUI 自己的飞控 UI：叠加在游戏原生画面上，只画 HUD 和按键提示，不重复画低分辨率预览 */
    private void drawFlightHud(GuiGraphics gui) {
        Minecraft mc = Minecraft.getInstance();
        FlightModeManager.FlightState state = FlightModeManager.INSTANCE.getState();
        if (state == null) return;

        RenderSystem.disableScissor();
        RenderSystem.disableDepthTest();

        var font = mc.font;
        List<String> lines = new ArrayList<>();
        lines.add("Flight Camera" + (FlightModeManager.INSTANCE.isSlowDown() ? "  [SLOW]" : ""));
        lines.add(String.format("XYZ: %.3f / %.3f / %.3f", state.pos.x, state.pos.y, state.pos.z));
        lines.add(String.format("Yaw: %.2f  Pitch: %.2f  Roll: %.2f", state.yaw, state.pitch, state.roll));
        lines.add(String.format("FOV: %.2f  Zoom: %.2f", state.fov, state.zoom));
        lines.add("Mode: " + (FlightModeManager.INSTANCE.isModeAbsolute() ? "ABSOLUTE" : "RELATIVE"));
        drawPanel(gui, font, 8, 8, lines);

        List<String> hints = buildFlightHints(mc.options);
        int lineH = font.lineHeight + 1;
        int maxW = 0;
        for (String h : hints) maxW = Math.max(maxW, font.width(h));
        int bw = maxW + 6;
        int bh = hints.size() * lineH + 6;
        int hx = width - bw - 12;
        int hy = height - bh - 12;
        drawPanel(gui, font, hx, hy, hints);

        RenderSystem.enableDepthTest();
    }

    private List<String> buildFlightHints(Options opts) {
        List<String> rows = new ArrayList<>();
        rows.add(I18n.get("editor.flight.key.look") + ": 鼠标");
        rows.add(I18n.get("editor.flight.key.move") + ": " +
                keyLabel(opts.keyUp) + " " + keyLabel(opts.keyLeft) + " " + keyLabel(opts.keyDown) + " " + keyLabel(opts.keyRight));
        rows.add(I18n.get("editor.flight.key.up_down") + ": " +
                keyLabel(opts.keyJump) + " " + keyLabel(opts.keyShift));
        rows.add(I18n.get("editor.flight.key.slow") + ": Ctrl");
        rows.add(I18n.get("editor.flight.key.roll") + ": " +
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_LEFT) + " " + keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_RIGHT));
        rows.add(I18n.get("editor.flight.key.fov") + ": " +
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_FOV_OUT) + " " + keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_FOV_IN));
        rows.add(I18n.get("editor.flight.key.zoom") + ": " +
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_OUT) + " " + keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_IN));
        rows.add(I18n.get("editor.flight.key.save_mode") + ": " + keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_MODE));
        rows.add(I18n.get("editor.flight.key.reset_optics") + ": " + keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_RESET_OPTICS));
        rows.add(I18n.get("editor.flight.key.save_exit") + ": " + keyLabel(CinematicKeyBindings.EDITOR_FLIGHT));
        rows.add(I18n.get("editor.flight.key.cancel") + ": Esc");
        return rows;
    }

    private static String keyLabel(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    private void drawPanel(GuiGraphics gui, Font font, int x, int y, List<String> lines) {
        int lineH = font.lineHeight + 1;
        int w = 0;
        for (String line : lines) w = Math.max(w, font.width(line));
        int h = lines.size() * lineH;
        gui.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0x80000000);
        for (int i = 0; i < lines.size(); i++) {
            gui.drawString(font, lines.get(i), x, y + i * lineH, 0xFFE0E0E0, false);
        }
    }

    /** 广播飞控模式下的实时相机状态 */
    private void broadcastFlightState() {
        FlightModeManager.FlightState state = FlightModeManager.INSTANCE.getState();
        JsonObject data = new JsonObject();
        data.addProperty("x", state.pos.x);
        data.addProperty("y", state.pos.y);
        data.addProperty("z", state.pos.z);
        data.addProperty("yaw", state.yaw);
        data.addProperty("pitch", state.pitch);
        data.addProperty("roll", state.roll);
        data.addProperty("fov", state.fov);
        data.addProperty("zoom", state.zoom);
        broadcast("flight.state", data);
    }

    /** 广播飞控模式退出（携带最终参数 + 相对模式基准点） */
    private void broadcastFlightExit(boolean cancelled) {
        JsonObject data = new JsonObject();
        data.addProperty("cancelled", cancelled);
        if (!cancelled) {
            FlightModeManager.FlightState state = FlightModeManager.INSTANCE.getState();
            data.addProperty("x", state.pos.x);
            data.addProperty("y", state.pos.y);
            data.addProperty("z", state.pos.z);
            data.addProperty("yaw", state.yaw);
            data.addProperty("pitch", state.pitch);
            data.addProperty("roll", state.roll);
            data.addProperty("fov", state.fov);
            data.addProperty("zoom", state.zoom);
            data.addProperty("absolute", FlightModeManager.INSTANCE.isModeAbsolute());
            // RELATIVE 写回需要基准点：当前玩家位置/触发点
            Minecraft mc = Minecraft.getInstance();
            Vec3 base = mc.player != null ? mc.player.position() : Vec3.ZERO;
            data.addProperty("baseX", base.x);
            data.addProperty("baseY", base.y);
            data.addProperty("baseZ", base.z);
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
        if (FlightModeManager.INSTANCE.isActive()) {
            FlightModeManager.INSTANCE.cancel();
        }
        if (activeScreen == this) activeScreen = null;
        CameraManager.INSTANCE.exitPreview();
        WebFrameCapture.destroy();
        WebEditorServer.INSTANCE.stop();
        CinematicKeyBindings.notifyEditorClosed();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
