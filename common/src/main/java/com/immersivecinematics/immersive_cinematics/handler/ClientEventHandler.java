package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.control.SkipHudRenderer;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.Minecraft;

public class ClientEventHandler {

    public static void register() {
        // 启动时确保游戏根必要目录存在：resource（播放读取音频/图片）+ scripts（编辑器使用）
        com.immersivecinematics.immersive_cinematics.util.ResourcePath.ensureDir();
        try {
            java.nio.file.Files.createDirectories(
                    Minecraft.getInstance().gameDirectory.toPath()
                            .resolve("immersive_cinematics").resolve("scripts"));
        } catch (Exception ignored) {
            // 目录已存在/权限受限即跳过（ResourcePath.ensureDir 已处理 resource；scripts 由编辑器按需创建）
        }

        // ===== 按键注册 =====

        KeyMappingRegistry.register(CinematicKeyBindings.SKIP_KEY);
        if (ImmersiveCinematics.EDITOR_ENABLED && CinematicKeyBindings.EDITOR_KEY != null) {
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_KEY);
            // 编辑器单键快捷键（可绑定）
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_PLAY_PAUSE);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_ADD_MARKER);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_SET_LOOP_IN);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_SET_LOOP_OUT);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_PLAYHEAD_LEFT);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_PLAYHEAD_RIGHT);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_NUDGE_UP);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_NUDGE_DOWN);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_HOME);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_END);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_PAGE_UP);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_PAGE_DOWN);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_CLIP_START);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_CLIP_END);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_PLAY_CLIP);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_DELETE);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_FRAME_ALL);
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_FLIGHT);
        }

        // ===== 客户端 Tick =====

        ClientTickEvent.CLIENT_POST.register(mc -> {
            CameraManager.INSTANCE.tick();
            CinematicKeyBindings.onClientTick();
            // 区块预加载：客户端请求器（开始 PRELOAD / 位置上报 / 结束 RELEASE）
            com.immersivecinematics.immersive_cinematics.trigger.client.PreloadRequester.INSTANCE.tick(mc);
            // N1：ACK 超时重发检查（客户端侧）
            com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.tick();
            // D2：世界退出/断线时紧急停止（防止 OpenAL 音频残留播放）
            if (mc.level == null && CameraManager.INSTANCE.isActive()) {
                CameraManager.INSTANCE.emergencyStop();
            }
        });

        // ===== HUD 渲染 =====

        // 电影黑边覆盖层（先画，作底层——letterbox 盖住世界画面，但不得盖住 HUD 元素）
        ClientGuiEvent.RENDER_HUD.register((graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            CinematicOverlay.render(graphics,
                    mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight());
        });
        // 跳过提示 HUD（后画，最上层——长按跳过为整体：文字+图标+环，不被 letterbox 覆盖）
        ClientGuiEvent.RENDER_HUD.register((graphics, deltaTracker) -> {
            SkipHudRenderer.render(graphics);
        });
    }
}
