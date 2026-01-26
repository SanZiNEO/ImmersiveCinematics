package com.example.immersive_cinematics.handler;

import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;

@Mod.EventBusSubscriber(Dist.CLIENT)
public class ClientForgeEvents {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        // 只要电影模式开启，就强制清空所有移动输入
        if (CinematicManager.getInstance().isCinematicActive()) {
            var input = event.getInput();
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.ClientTickEvent.Phase.START) {
            KeyHandler.getInstance().onClientTick();
        }
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        // 使用 ViewportEvent 实现帧级精度的相机更新
        CinematicManager.getInstance().onRenderUpdate(event.getPartialTick());
    }

    @SubscribeEvent
    public static void onComputeFov(net.minecraftforge.client.event.ViewportEvent.ComputeFov event) {
        CinematicManager cinematicManager = CinematicManager.getInstance();
        if (cinematicManager.isCinematicActive() && cinematicManager.getVirtualCamera() != null) {
            CinematicCameraEntity virtualCamera = cinematicManager.getVirtualCamera();
            event.setFOV(virtualCamera.getCurrentFov());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        if (CinematicManager.getInstance().isCinematicActive()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderHand(RenderHandEvent event) {
        if (CinematicManager.getInstance().isCinematicActive()) {
            event.setCanceled(true);
        }
    }
}