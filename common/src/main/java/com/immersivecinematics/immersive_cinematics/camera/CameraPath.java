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
 */
public class CameraPath {

    // --- 当前值 ---
    private Vec3 currentPosition = Vec3.ZERO;

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
     * 重置到原点
     */
    public void reset() {
        currentPosition = Vec3.ZERO;
    }
}
