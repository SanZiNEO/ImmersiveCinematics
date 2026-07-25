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
        // ===== 按键注册 =====

        KeyMappingRegistry.register(CinematicKeyBindings.SKIP_KEY);
        if (ImmersiveCinematics.EDITOR_ENABLED && CinematicKeyBindings.EDITOR_KEY != null) {
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_KEY);
        }

        // ===== 客户端 Tick =====

        ClientTickEvent.CLIENT_POST.register(mc -> {
            CameraManager.INSTANCE.tick();
            CinematicKeyBindings.onClientTick();
        });

        // ===== HUD 渲染 =====

        // 跳过提示 HUD（追加绘制）
        ClientGuiEvent.RENDER_HUD.register((graphics, deltaTracker) -> {
            SkipHudRenderer.render(graphics);
        });
        // 电影黑边覆盖层（追加绘制）
        ClientGuiEvent.RENDER_HUD.register((graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            CinematicOverlay.render(graphics,
                    mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight());
        });
    }
}
