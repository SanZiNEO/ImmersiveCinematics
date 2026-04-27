package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.world.phys.Vec3;

/**
 * 相机路径控制器 — 只管理相机在世界中的位置轨迹 (x, y, z)
 * 完全不知道 CameraProperties 的存在
 */
public class CameraPath {

    // --- 当前值 ---
    private Vec3 currentPosition = Vec3.ZERO;

    // --- 目标值 ---
    private Vec3 targetPosition = Vec3.ZERO;

    // --- 插值控制 ---
    private Vec3 startPosition = Vec3.ZERO;
    private float transitionDuration = 0f;   // 过渡时长（秒），0 = 瞬时
    private float transitionProgress = 1f;   // 0~1，1 = 已完成

    /**
     * 设置目标位置（带过渡时长）
     * @param pos 目标位置
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetPosition(Vec3 pos, float duration) {
        this.startPosition = this.currentPosition;
        this.targetPosition = pos;
        this.transitionDuration = duration;
        this.transitionProgress = 0f;

        // duration 为 0 时瞬时跳转
        if (duration <= 0f) {
            this.currentPosition = pos;
            this.transitionProgress = 1f;
        }
    }

    /**
     * 每tick驱动插值
     * @param deltaTime 距离上一tick的时间（秒）
     */
    public void tick(float deltaTime) {
        if (transitionProgress < 1f) {
            transitionProgress = Math.min(1f, transitionProgress + deltaTime / transitionDuration);
            currentPosition = startPosition.lerp(targetPosition, transitionProgress);
        }
    }

    /**
     * 获取当前坐标（供 CameraManager / Mixin 读取）
     */
    public Vec3 getPosition() {
        return currentPosition;
    }

    /**
     * 重置到原点
     */
    public void reset() {
        currentPosition = Vec3.ZERO;
        targetPosition = Vec3.ZERO;
        startPosition = Vec3.ZERO;
        transitionDuration = 0f;
        transitionProgress = 1f;
    }
}
