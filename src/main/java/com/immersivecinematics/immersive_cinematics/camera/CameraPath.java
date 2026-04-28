package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.world.phys.Vec3;

/**
 * 相机路径控制器 — 只管理相机在世界中的位置轨迹 (x, y, z)
 * 完全不知道 CameraProperties 的存在
 */
public class CameraPath {

    // --- 当前值 ---
    private Vec3 currentPosition = Vec3.ZERO;

    // --- 上一tick的值（用于帧级 partialTick 插值） ---
    private Vec3 previousPosition = Vec3.ZERO;

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
     * 每tick开始时保存当前值为"上一tick值"，供渲染帧 partialTick 插值使用。
     * 必须在 CameraManager.tick() 开头、所有 setTargetPosition 调用之前执行。
     */
    public void savePreviousTick() {
        this.previousPosition = this.currentPosition;
    }

    /**
     * 获取 partialTick 插值后的位置，供 CameraMixin.onSetup 渲染帧使用。
     * @param partialTick 渲染帧进度 [0, 1)，来自 Camera.setup() 的参数
     * @return previousPosition.lerp(currentPosition, partialTick)
     */
    public Vec3 getPositionInterpolated(float partialTick) {
        return previousPosition.lerp(currentPosition, partialTick);
    }

    /**
     * 重置到原点
     */
    public void reset() {
        currentPosition = Vec3.ZERO;
        previousPosition = Vec3.ZERO;
        targetPosition = Vec3.ZERO;
        startPosition = Vec3.ZERO;
        transitionDuration = 0f;
        transitionProgress = 1f;
    }
}
