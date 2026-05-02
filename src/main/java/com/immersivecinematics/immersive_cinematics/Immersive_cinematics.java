package com.immersivecinematics.immersive_cinematics;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.command.CinematicCommand;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.handler.HudOverlayHandler;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
/**
 * ImmersiveCinematics 模组入口
 * <p>
 * 纯客户端电影摄影模组，提供相机动画系统用于制作游戏内过场动画。
 * 核心架构：CameraProperties + CameraPath (纯POJO) ↔ CameraManager (唯一桥梁) ↔ Mixin (只读Manager)
 */
@Mod(Immersive_cinematics.MODID)
public class Immersive_cinematics {

    public static final String MODID = "immersive_cinematics";

    public Immersive_cinematics() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册 Forge 事件总线
        MinecraftForge.EVENT_BUS.register(this);

        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 注册电影覆盖层到 MOD 事件总线（RegisterGuiOverlaysEvent 是 MOD 事件）
        modEventBus.addListener(CinematicOverlay::onRegisterGuiOverlays);

        // 注册按键绑定（FMLClientSetupEvent 是 MOD 事件）
        modEventBus.addListener(this::onClientSetup);

        // 注册客户端事件处理器
        MinecraftForge.EVENT_BUS.register(ClientTickEvents.class);
        MinecraftForge.EVENT_BUS.register(ClientHudEvents.class);
        MinecraftForge.EVENT_BUS.register(ClientCameraEvents.class);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        CinematicKeyBindings.register();
    }

    /**
     * 客户端 Tick 事件 — 驱动 CameraManager.tick() 和按键检测
     */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientTickEvents {

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                CameraManager.INSTANCE.tick();
                CinematicKeyBindings.onClientTick();
            }
        }
    }

    /**
     * 客户端 HUD overlay 事件 — 电影模式下隐藏所有 HUD（白名单机制）
     */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientHudEvents {

        @SubscribeEvent
        public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
            HudOverlayHandler.onRenderGuiOverlayPre(event);
        }
    }

    /**
     * 命令注册事件 — 注册 /cinematic 命令
     * <p>
     * RegisterCommandsEvent 在 FORGE 总线上触发，服务端执行。
     * 命令内部通过 Minecraft.getInstance().execute() 将客户端操作调度到客户端线程。
     */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class CommandEvents {

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CinematicCommand.register(event.getDispatcher());
        }
    }

    /**
     * 客户端相机事件 — 通过 Forge 事件设置 roll
     * <p>
     * Forge 的 ViewportEvent.ComputeCameraAngles 在 GameRenderer.renderLevel() 中
     * 相机旋转应用到 PoseStack 之前触发，调用 event.setRoll() 即可实现滚转。
     * <p>
     * GameRenderer.renderLevel() 中的执行顺序：
     * 1. camera.setup() → 我们的 CameraMixin 拦截，设置自定义 position/yaw/pitch
     * 2. Forge 事件触发 → 本方法设置 roll
     * 3. camera.setAnglesInternal(event.yaw, event.pitch) → 用事件值覆写相机角度
     * 4. poseStack.mulPose(Axis.ZP.rotationDegrees(event.roll)) → roll 应用到视图矩阵 ✅
     * 5. poseStack.mulPose(Axis.XP.rotationDegrees(pitch)) → pitch 应用到视图矩阵
     * 6. poseStack.mulPose(Axis.YP.rotationDegrees(yaw + 180)) → yaw 应用到视图矩阵
     */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientCameraEvents {

        @SubscribeEvent
        public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            CameraManager mgr = CameraManager.INSTANCE;
            if (mgr.isActive()) {
                // 🎬 帧回调驱动模式：直接读取当前值，不需要 partialTick 插值
                // onRenderFrame() 已在 CameraMixin.onSetup() 中被调用，值已精确重算
                float roll = mgr.getProperties().getRoll();
                event.setRoll(roll);
            }
        }
    }
}
