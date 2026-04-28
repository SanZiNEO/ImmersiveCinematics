package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 相机统一调度器 — 单例模式
 * CameraProperties 和 CameraPath 互不知晓，CameraManager 是唯一桥梁
 * Mixin 层不直接依赖 CameraProperties / CameraPath，统一从 CameraManager 读取
 */
public class CameraManager {

    public static final CameraManager INSTANCE = new CameraManager();

    private final CameraProperties properties = new CameraProperties();
    private final CameraPath path = new CameraPath();
    private final CameraTestPlayer testPlayer = new CameraTestPlayer();
    private boolean active = false;

    // ========== 生命周期 ==========

    /**
     * 从玩家当前位置/朝向激活相机
     */
    public void activate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 playerPos = mc.player.position();
        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();

        // 瞬时设置（duration=0），不插值
        path.setTargetPosition(playerPos, 0f);
        properties.setTargetYaw(playerYaw, 0f);
        properties.setTargetPitch(playerPitch, 0f);

        active = true;

        // 阶段1：激活后启动测试播放器
        testPlayer.start();
    }

    /**
     * 停用相机，恢复玩家视角
     */
    public void deactivate() {
        active = false;
        testPlayer.stop();
        reset();
    }

    /**
     * 切换相机激活/停用
     */
    public void toggle() {
        if (active) {
            deactivate();
        } else {
            activate();
        }
    }

    // ========== 每tick驱动 ==========

    /**
     * 由 ClientTickEvent 调用
     * 驱动 Properties 和 Path 的 tick 插值
     */
    public void tick() {
        if (!active) return;
        float deltaTime = 1f / 20f; // 每tick 0.05秒

        // 每tick开头保存当前值作为"上一帧基准"，供渲染帧 partialTick 插值使用。
        // 必须在所有 setTargetXxx() 调用之前执行，确保 previous 值不被覆盖。
        path.savePreviousTick();
        properties.savePreviousTick();

        // 阶段1：驱动测试播放器（在 properties/path tick 之前）
        testPlayer.tick(deltaTime);

        properties.tick(deltaTime);
        path.tick(deltaTime);

        // 播放结束后自动停用
        if (testPlayer.isFinished()) {
            deactivate();
        }
    }

    // ========== Mixin 读取接口 ==========

    public CameraProperties getProperties() {
        return properties;
    }

    public CameraPath getPath() {
        return path;
    }

    public boolean isActive() {
        return active;
    }

    // ========== 便捷方法 ==========

    /**
     * 重置两个子系统
     */
    public void reset() {
        properties.reset();
        path.reset();
    }
}
