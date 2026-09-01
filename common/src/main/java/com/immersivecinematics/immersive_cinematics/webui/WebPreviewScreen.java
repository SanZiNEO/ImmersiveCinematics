package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * WebUI 编辑器预览屏幕。
 *
 * <p>进入此屏幕后启动 WebSocket 服务端，将游戏画面流式传输给独立 Editor 客户端。
 * 所有 EDITOR_* 绑定键在此屏幕内生效，通过 WebSocket 广播给前端执行对应操作。
 * 不自动加载脚本，播放/加载完全由 WebUI 端控制。</p>
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
        // 不自动加载脚本，等待 WebUI 端通过 editor.pushScript 推送
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        minecraft.renderBuffers().bufferSource().endBatch();
        WebFrameCapture.capture(minecraft);
        WebFrameStreamer.onFrame();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // F6 / ESC 关闭
        if (CinematicKeyBindings.EDITOR_KEY != null && CinematicKeyBindings.EDITOR_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }

        // ── EDITOR_* 绑定键接线：触发后通过 WebSocket 广播给前端 ──

        if (CinematicKeyBindings.EDITOR_PLAY_PAUSE.matches(keyCode, scanCode)) {
            broadcast("editor.play_pause", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_ADD_MARKER.matches(keyCode, scanCode)) {
            broadcast("editor.add_marker", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_SET_LOOP_IN.matches(keyCode, scanCode)) {
            broadcast("editor.set_loop_in", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_SET_LOOP_OUT.matches(keyCode, scanCode)) {
            broadcast("editor.set_loop_out", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_PLAYHEAD_LEFT.matches(keyCode, scanCode)) {
            JsonObject data = new JsonObject();
            data.addProperty("direction", -1);
            data.addProperty("large", hasShift());
            broadcast("editor.nudge_playhead", data);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_PLAYHEAD_RIGHT.matches(keyCode, scanCode)) {
            JsonObject data = new JsonObject();
            data.addProperty("direction", 1);
            data.addProperty("large", hasShift());
            broadcast("editor.nudge_playhead", data);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_HOME.matches(keyCode, scanCode)) {
            broadcast("editor.goto_start", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_END.matches(keyCode, scanCode)) {
            broadcast("editor.goto_end", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_DELETE.matches(keyCode, scanCode)) {
            broadcast("editor.delete_selected", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_FRAME_ALL.matches(keyCode, scanCode)) {
            broadcast("editor.frame_all", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_CLIP_START.matches(keyCode, scanCode)) {
            broadcast("editor.goto_clip_start", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_CLIP_END.matches(keyCode, scanCode)) {
            broadcast("editor.goto_clip_end", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_PLAY_CLIP.matches(keyCode, scanCode)) {
            broadcast("editor.play_clip", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_NUDGE_UP.matches(keyCode, scanCode)) {
            broadcast("editor.nudge_up", null);
            return true;
        }
        if (CinematicKeyBindings.EDITOR_NUDGE_DOWN.matches(keyCode, scanCode)) {
            broadcast("editor.nudge_down", null);
            return true;
        }

        // Web 编辑模式下不把游戏输入透传给玩家
        return true;
    }

    private boolean hasShift() {
        long window = minecraft.getWindow().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** 向所有连接的 WebUI 客户端广播事件 */
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
        CameraManager.INSTANCE.exitPreview();
        WebFrameCapture.destroy();
        WebEditorServer.INSTANCE.stop();
        CinematicKeyBindings.notifyEditorClosed();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
