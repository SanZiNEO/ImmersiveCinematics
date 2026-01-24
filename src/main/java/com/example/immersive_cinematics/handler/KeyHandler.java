package com.example.immersive_cinematics.handler;

import com.example.immersive_cinematics.ImmersiveCinematics;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
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
        // 在 Forge 1.20.1 中，KeyMapping 会在创建时自动注册
        // 但为了确保兼容性，我们可以使用以下方式
        try {
            // 尝试使用反射查找并调用注册方法
            Class<?> clientRegistryClass = Class.forName("net.minecraftforge.client.ClientRegistry");
            java.lang.reflect.Method registerMethod = clientRegistryClass.getMethod("registerKeyBinding", KeyMapping.class);
            registerMethod.invoke(null, toggleCinematicKey);
            registerMethod.invoke(null, increaseFovKey);
            registerMethod.invoke(null, decreaseFovKey);
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

        // 处理 FOV 调节按键 - 长按每帧递增/递减
        CinematicManager cinematicManager = CinematicManager.getInstance();
        if (cinematicManager.isCinematicActive() && cinematicManager.getVirtualCamera() != null) {
            if (increaseFovKey.isDown()) {
                float currentFov = cinematicManager.getVirtualCamera().getCurrentFov();
                cinematicManager.getVirtualCamera().setCurrentFov(currentFov + 0.5f);
            }

            if (decreaseFovKey.isDown()) {
                float currentFov = cinematicManager.getVirtualCamera().getCurrentFov();
                cinematicManager.getVirtualCamera().setCurrentFov(currentFov - 0.5f);
            }

            // 限制 FOV 范围
            float fov = cinematicManager.getVirtualCamera().getCurrentFov();
            if (fov < 10.0f) {
                cinematicManager.getVirtualCamera().setCurrentFov(10.0f);
            } else if (fov > 120.0f) {
                cinematicManager.getVirtualCamera().setCurrentFov(120.0f);
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
}