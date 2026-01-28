package com.example.immersive_cinematics.handler;

import com.example.immersive_cinematics.ImmersiveCinematics;
import com.example.immersive_cinematics.director.DirectorScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ImmersiveCinematics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyHandler {

    private static KeyHandler instance;

    // 开关电影模式的按键
    private final KeyMapping toggleCinematicKey;

    // 增加 FOV 的按键
    private final KeyMapping increaseFovKey;

    // 减少 FOV 的按键
    private final KeyMapping decreaseFovKey;

    // 打开导演界面的按键
    private final KeyMapping openDirectorScreenKey;

    private KeyHandler() {
        toggleCinematicKey = new KeyMapping(
                "key.immersive_cinematics.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                "category.immersive_cinematics"
        );

        increaseFovKey = new KeyMapping(
                "key.immersive_cinematics.increase_fov",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                "category.immersive_cinematics"
        );

        decreaseFovKey = new KeyMapping(
                "key.immersive_cinematics.decrease_fov",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                "category.immersive_cinematics"
        );

        openDirectorScreenKey = new KeyMapping(
                "key.immersive_cinematics.director_screen",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.immersive_cinematics"
        );
    }

    public static KeyHandler getInstance() {
        if (instance == null) {
            instance = new KeyHandler();
        }
        return instance;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 注册按键绑定 (使用 Forge 1.20.1 版本的正确方法)
            getInstance().registerKeyBindings();
        });
    }

    private void registerKeyBindings() {
        // 在 Forge 1.20+ 中，KeyMapping 会在创建时自动注册到游戏中
        // 我们只需要确保它们在客户端设置时被正确初始化
        // 使用反射方式尝试注册，以兼容不同版本
        try {
            Class<?> clientRegistryClass = Class.forName("net.minecraftforge.client.ClientRegistry");
            java.lang.reflect.Method registerMethod = clientRegistryClass.getMethod("registerKeyBinding", KeyMapping.class);
            registerMethod.invoke(null, toggleCinematicKey);
            registerMethod.invoke(null, increaseFovKey);
            registerMethod.invoke(null, decreaseFovKey);
            registerMethod.invoke(null, openDirectorScreenKey);
        } catch (Exception e) {
            // 如果反射失败，说明 KeyMapping 已经自动注册
            ImmersiveCinematics.LOGGER.warn("Failed to register key bindings via reflection: " + e.getMessage());
        }
    }

    public void onClientTick() {
        // 处理开关按键
        while (toggleCinematicKey.consumeClick()) {
            CinematicManager.getInstance().toggleCinematic();
        }

        // 处理打开导演界面按键
        while (openDirectorScreenKey.consumeClick()) {
            Minecraft.getInstance().setScreen(new DirectorScreen());
        }

        // 处理 FOV 调节按键 - 长按每帧递增/递减
        CinematicManager cinematicManager = CinematicManager.getInstance();
        if (cinematicManager.isCinematicActive()) {
            if (increaseFovKey.isDown()) {
                double currentFov = cinematicManager.getCurrentFOV();
                cinematicManager.setCustomCameraFOV(currentFov + 0.5);
            }

            if (decreaseFovKey.isDown()) {
                double currentFov = cinematicManager.getCurrentFOV();
                cinematicManager.setCustomCameraFOV(currentFov - 0.5);
            }

            // 限制 FOV 范围
            double fov = cinematicManager.getCurrentFOV();
            if (fov < 10.0) {
                cinematicManager.setCustomCameraFOV(10.0);
            } else if (fov > 120.0) {
                cinematicManager.setCustomCameraFOV(120.0);
            }
        }
    }

    public KeyMapping getToggleCinematicKey() {
        return toggleCinematicKey;
    }

    public KeyMapping getIncreaseFovKey() {
        return increaseFovKey;
    }

    public KeyMapping getDecreaseFovKey() {
        return decreaseFovKey;
    }

    public KeyMapping getOpenDirectorScreenKey() {
        return openDirectorScreenKey;
    }
}
