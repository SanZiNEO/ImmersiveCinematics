package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.camera.CinematicCameraEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class CinematicCameraHandler {

    private static CinematicCameraHandler instance;
    private CinematicCameraEntity cameraEntity;
    private boolean isActive = false;

    public static CinematicCameraHandler getInstance() {
        if (instance == null) {
            instance = new CinematicCameraHandler();
        }
        return instance;
    }

    public void toggleCamera() {
        Minecraft mc = Minecraft.getInstance();
        
        if (mc.level == null || mc.player == null) {
            return;
        }

        if (isActive) {
            // 关闭相机
            disableCamera();
        } else {
            // 开启相机
            enableCamera();
        }
    }

    private void enableCamera() {
        Minecraft mc = Minecraft.getInstance();
        
        // 创建相机实体
        Vec3 playerPos = mc.player.position();
        cameraEntity = new CinematicCameraEntity(mc.level, playerPos, mc.player.getYRot(), mc.player.getXRot());
        
        // 添加到世界
        mc.level.addFreshEntity(cameraEntity);
        
        isActive = true;
    }

    private void disableCamera() {
        if (cameraEntity != null && cameraEntity.isAlive()) {
            cameraEntity.remove(Entity.RemovalReason.DISCARDED);
            cameraEntity = null;
        }
        
        isActive = false;
    }

    public CinematicCameraEntity getCameraEntity() {
        return cameraEntity;
    }

    public boolean isActive() {
        return isActive;
    }
}