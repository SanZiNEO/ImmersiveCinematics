package com.example.immersive_cinematics.handler;

import com.example.immersive_cinematics.ImmersiveCinematics;
import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import com.example.immersive_cinematics.trigger.WorldEventDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod.EventBusSubscriber(
    modid = ImmersiveCinematics.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,  // 必须显式指定 Forge 总线
    value = Dist.CLIENT  // 客户端事件
)
public class ClientForgeEvents {

    private static final Logger LOGGER = LogManager.getLogger();

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
        } else {
            // 调用 TimelineProcessor 更新
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                com.example.immersive_cinematics.director.TimelineProcessor.getInstance().update(mc.player, 0.0f);
            }
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
        if (cinematicManager.isCinematicActive()) {
            event.setFOV((float) cinematicManager.getCurrentFOV());
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

    // 监听维度变化
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() == Minecraft.getInstance().player) {
            String fromDimension = event.getFrom().location().toString();
            String toDimension = event.getTo().location().toString();
            
            LOGGER.info("Player changed dimension from {} to {}", fromDimension, toDimension);
            
            // 在聊天框中显示维度变化信息
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§a维度变化: §r从 " + fromDimension + " 到 " + toDimension),
                        false
                );
            }
        }
    }
}