package com.example.immersive_cinematics.handler;

import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import net.minecraft.client.Minecraft;

public class CinematicManager {

    private static CinematicManager instance;

    private CinematicCameraEntity virtualCamera;
    private boolean isCinematicActive = false;

    private CinematicManager() {
    }

    public static CinematicManager getInstance() {
        if (instance == null) {
            instance = new CinematicManager();
        }
        return instance;
    }

    public void startCinematic() {
        if (isCinematicActive || Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
            return;
        }

        // 创建虚拟摄像机实体
        virtualCamera = new CinematicCameraEntity(Minecraft.getInstance().player.getId() + 1000);
        virtualCamera.spawn();

        // 将摄像机设置到玩家头顶 10 格
        double playerX = Minecraft.getInstance().player.getX();
        double playerY = Minecraft.getInstance().player.getY() + 10;
        double playerZ = Minecraft.getInstance().player.getZ();
        virtualCamera.setPos(playerX, playerY, playerZ);
        virtualCamera.setYRot(Minecraft.getInstance().player.getYRot());
        virtualCamera.setXRot(Minecraft.getInstance().player.getXRot());

        // 设置为当前摄像机
        Minecraft.getInstance().setCameraEntity(virtualCamera);
        isCinematicActive = true;
    }

    public void stopCinematic() {
        if (!isCinematicActive || Minecraft.getInstance().player == null) {
            return;
        }

        // 恢复玩家摄像机
        Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player);

        // 销毁虚拟摄像机
        if (virtualCamera != null) {
            virtualCamera.despawn();
            virtualCamera = null;
        }

        isCinematicActive = false;
    }

    public void toggleCinematic() {
        if (isCinematicActive) {
            stopCinematic();
        } else {
            startCinematic();
        }
    }

    public boolean isCinematicActive() {
        return isCinematicActive;
    }

    public CinematicCameraEntity getVirtualCamera() {
        return virtualCamera;
    }

    public net.minecraft.client.player.LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    public boolean isCinematicMode() {
        return isCinematicActive;
    }

    // 每帧更新 - 强制虚拟摄像机保持在玩家头顶
    public void onClientTick() {
        if (isCinematicActive && virtualCamera != null && Minecraft.getInstance().player != null) {
            double playerX = Minecraft.getInstance().player.getX();
            double playerY = Minecraft.getInstance().player.getY() + 10;
            double playerZ = Minecraft.getInstance().player.getZ();
            virtualCamera.setPos(playerX, playerY, playerZ);
        }
    }
}