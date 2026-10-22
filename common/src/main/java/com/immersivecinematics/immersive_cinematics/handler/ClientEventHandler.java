package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.control.SkipHudRenderer;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 客户端事件处理：把平台事件翻译成相机 / 按键 / 覆盖层的调用。
 *
 * 只保留纯逻辑；平台（Fabric/Forge）负责把原生事件转发到这里。
 */
public final class ClientEventHandler {

    /** 平台注册按键绑定的回调（各加载器注册表 API 不同，由平台实现）。 */
    @FunctionalInterface
    public interface KeyMappingRegistrar {
        /** 把单个按键绑定交给平台注册表。 */
        void register(KeyMapping keyMapping);
    }

    private ClientEventHandler() {}

    /** 客户端初始化：确保资源目录与 scripts 目录存在，编辑器首次保存时无需再建目录。 */
    public static void onClientInit() {
        com.immersivecinematics.immersive_cinematics.util.ResourcePath.ensureDir();
        try {
            java.nio.file.Files.createDirectories(
                    Minecraft.getInstance().gameDirectory.toPath()
                            .resolve("immersive_cinematics").resolve("scripts"));
        } catch (Exception ignored) {
            // 目录已存在/权限受限即跳过（ResourcePath.ensureDir 已处理 resource；scripts 由编辑器按需创建）
        }
    }

    /** 注册本 mod 的全部按键：跳过键 + WebUI 预览屏与飞控相关按键。 */
    public static void registerKeyMappings(KeyMappingRegistrar registrar) {
        registrar.register(CinematicKeyBindings.SKIP_KEY);
        // WebUI 预览屏与飞控相关按键（游戏内 Java 编辑器退役后仅剩这些）
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT);
        registrar.register(CinematicKeyBindings.EDITOR_WEBUI_OPEN);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_FOV_IN);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_FOV_OUT);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_IN);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_OUT);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_LEFT);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_RIGHT);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_MODE);
        registrar.register(CinematicKeyBindings.EDITOR_FLIGHT_RESET_OPTICS);
    }

    /**
     * 每 tick 驱动相机、按键、触发相关客户端组件。
     *
     * 无世界而相机仍活跃（如退出世界时平台漏发停播）→ emergencyStop，
     * 否则相机状态会跨世界残留，进入下一个存档时直接接管视角。
     */
    public static void onClientTick(Minecraft mc) {
        CameraManager.INSTANCE.tick();
        CinematicKeyBindings.onClientTick();
        com.immersivecinematics.immersive_cinematics.trigger.client.PreloadRequester.INSTANCE.tick(mc);
        com.immersivecinematics.immersive_cinematics.trigger.client.AudioListenerReporter.INSTANCE.tick(mc);
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.tick();
        if (mc.level == null && CameraManager.INSTANCE.isActive()) {
            CameraManager.INSTANCE.emergencyStop();
        }
    }

    /** HUD 渲染：电影覆盖层 + 跳过提示，均在 GUI 缩放坐标系下绘制。 */
    public static void onRenderHud(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        CinematicOverlay.render(graphics,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight());
        SkipHudRenderer.render(graphics);
    }
}
