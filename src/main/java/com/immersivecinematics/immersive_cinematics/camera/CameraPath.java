package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.world.phys.Vec3;

/**
 * 相机路径控制器 — 只管理相机在世界中的位置轨迹 (x, y, z)
 * 完全不知道 CameraProperties 的存在
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - 不再使用 partialTick 插值
 * - 每渲染帧由 CameraTrackPlayer.onRenderFrame() 直接设置精确位置
 * - getPosition() 直接返回 currentPosition，无插值层
 * - 保留 setTargetPosition() + tick() 供 staged 缓冲区的过渡插值使用
 */
public class CameraPath {

    // --- 当前值 ---
    private Vec3 currentPosition = Vec3.ZERO;

    // --- 目标值（供 staged 缓冲区过渡插值使用） ---
    private Vec3 targetPosition = Vec3.ZERO;

    // --- 插值控制（供 staged 缓冲区过渡插值使用） ---
    private Vec3 startPosition = Vec3.ZERO;
    private float transitionDuration = 0f;   // 过渡时长（秒），0 = 瞬时
    private float transitionProgress = 1f;   // 0~1，1 = 已完成

    /**
     * 🎬 直接设置当前位置（帧回调驱动模式）
     * <p>
     * 由 CameraTrackPlayer.onRenderFrame() 每帧调用，
     * 直接写入精确计算的位置，无需过渡插值。
     * 类似 ReplayMod CameraEntity.setCameraPosition() 的 prevX=x 语义。
     *
     * @param pos 精确计算的相机位置
     */
    public void setPositionDirect(Vec3 pos) {
        this.currentPosition = pos;
        // 同步目标值，保持一致性
        this.targetPosition = pos;
        this.startPosition = pos;
        this.transitionProgress = 1f;
    }

    /**
     * 设置目标位置（带过渡时长）
     * <p>
     * 主要用于 staged 缓冲区预置关键帧时的过渡控制。
     * 在帧回调驱动模式下，段内插值由 CameraTrackPlayer 自己完成，
     * 此方法主要用于段间硬切换（duration=0）的 staged 预置。
     *
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
     * 每tick驱动过渡插值（供 staged 缓冲区使用）
     * <p>
     * 在帧回调驱动模式下，active 缓冲区的位置由 setPositionDirect() 直接设置，
     * 不需要 tick() 驱动。此方法保留用于 staged 缓冲区的过渡动画。
     *
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
     * <p>
     * 🎬 帧回调驱动模式下直接返回 currentPosition，
     * 不需要 partialTick 插值，因为每帧都已精确重算。
     */
    public Vec3 getPosition() {
        return currentPosition;
    }

    /**
     * 从另一个 CameraPath 实例覆盖当前状态（用于硬切换 commitStagedState）
     * <p>
     * 将 staged 缓冲区的状态原子替换到 active 缓冲区。
     *
     * @param source 源实例（通常是 staged 缓冲区）
     */
    public void overrideFrom(CameraPath source) {
        this.currentPosition = source.currentPosition;
        this.targetPosition = source.currentPosition;
        this.startPosition = source.currentPosition;
        this.transitionDuration = 0f;
        this.transitionProgress = 1f;
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
