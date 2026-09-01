package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * WebUI 画面通信原型：空 Screen，不绘制任何 GUI。
 *
 * <p>只做两件事：让 CameraManager 进入预览/播放状态，并把实际游戏画面通过
 * WebEditorServer 发给浏览器。预览模式会应用脚本 meta，保证与正式播放一致。</p>
 */
public class WebPreviewScreen extends Screen {

    private final Path scriptsDir;

    public WebPreviewScreen() {
        super(Component.literal("Web Preview"));
        this.scriptsDir = Paths.get("immersive_cinematics", "scripts");
    }

    @Override
    protected void init() {
        WebEditorServer.INSTANCE.start();
        System.out.println("[IC-WebUI] editor server at 127.0.0.1:" + WebEditorServer.DEFAULT_PORT);
        if (minecraft.player != null) {
            if (WebEditorServer.INSTANCE.hasClients()) {
                minecraft.player.displayClientMessage(
                        Component.literal("[ImmersiveCinematics] Editor 已连接"), false);
            } else {
                minecraft.player.displayClientMessage(
                        Component.literal("[ImmersiveCinematics] 请启动独立 Editor 客户端进行编辑"), false);
            }
        }

        String json = loadFirstScript();
        if (json != null) {
            CameraManager.INSTANCE.pushScript(json);
            CameraManager.INSTANCE.setTime(0f);
            // 严格等于正式播放：把脚本运行时 meta 应用到 CinematicController
            if (CameraManager.INSTANCE.getCurrentProperties() != null) {
                CinematicController.INSTANCE.apply(CameraManager.INSTANCE.getCurrentProperties());
            }
            CameraManager.INSTANCE.resume();
            System.out.println("[IC-WebUI] preview playing loaded script");
        } else {
            System.out.println("[IC-WebUI] no script found under " + scriptsDir.toAbsolutePath());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 与旧 EditorScreen 相同的捕获时序：先 flush GUI 顶点缓冲，再捕获。
        // 本 Screen 不画任何东西，所以捕获结果 == 实际画面。
        minecraft.renderBuffers().bufferSource().endBatch();
        WebFrameCapture.capture(minecraft);
        WebFrameStreamer.onFrame();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CinematicKeyBindings.EDITOR_KEY != null && CinematicKeyBindings.EDITOR_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // Web 编辑模式下不需要把游戏输入透传给玩家，全部消费掉。
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

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

    private String loadFirstScript() {
        if (!Files.exists(scriptsDir)) return null;
        try (Stream<Path> files = Files.walk(scriptsDir, 5)) {
            Path first = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
            if (first == null) return null;
            return Files.readString(first);
        } catch (IOException e) {
            System.err.println("[IC-WebUI] failed to load script: " + e.getMessage());
            return null;
        }
    }
}
