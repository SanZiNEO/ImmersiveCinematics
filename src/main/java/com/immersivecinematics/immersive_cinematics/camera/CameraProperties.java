package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;

/**
 * 相机属性控制器 — 管理相机的所有自身属性
 * 包含：朝向（yaw, pitch, roll）和 光学特征（FOV, DOF, Zoom）
 * 完全不知道 CameraPath 的存在
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - 不再使用 partialTick 插值
 * - 每渲染帧由 CameraTrackPlayer.onRenderFrame() 直接设置精确值
 * - getXxx() 直接返回 currentXxx，无插值层
 * - 保留 setTargetXxx() + tick() 供 staged 缓冲区的过渡插值使用
 * <p>
 * 注意：画幅比（aspectRatio）不在此处管理，因为它属于覆盖层系统，
 * 独立于镜头跳转逻辑，由 CameraManager 直接管理。
 */
public class CameraProperties {

    // --- 默认值 ---
    private static final float DEFAULT_FOV = 70.0f;
    private static final float DEFAULT_ROLL = 0.0f;
    private static final float DEFAULT_DOF = 0.0f;
    private static final float DEFAULT_ZOOM = 1.0f;

    // --- 当前值 ---
    private float currentYaw = 0f;
    private float currentPitch = 0f;
    private float currentRoll = 0f;
    private float currentFov = DEFAULT_FOV;
    private float currentDof = DEFAULT_DOF;
    private float currentZoom = DEFAULT_ZOOM;

    // --- 目标值（供 staged 缓冲区过渡插值使用） ---
    private float targetYaw = 0f;
    private float targetPitch = 0f;
    private float targetRoll = 0f;
    private float targetFov = DEFAULT_FOV;
    private float targetDof = DEFAULT_DOF;
    private float targetZoom = DEFAULT_ZOOM;

    // --- 插值起点（供 staged 缓冲区过渡插值使用） ---
    private float startYaw = 0f;
    private float startPitch = 0f;
    private float startRoll = 0f;
    private float startFov = DEFAULT_FOV;
    private float startDof = DEFAULT_DOF;
    private float startZoom = DEFAULT_ZOOM;

    // --- 插值控制 ---
    private float transitionDuration = 0f;   // 过渡时长（秒），0 = 瞬时
    private float transitionProgress = 1f;   // 0~1，1 = 已完成

    // ========== 🎬 直接设置方法（帧回调驱动模式） ==========

    /**
     * 🎬 直接设置 yaw（帧回调驱动模式）
     * <p>
     * 由 CameraTrackPlayer.onRenderFrame() 每帧调用，
     * 直接写入精确计算的值，无需过渡插值。
     *
     * @param yaw 精确计算的偏航角
     */
    public void setYawDirect(float yaw) {
        this.currentYaw = yaw;
        this.targetYaw = yaw;
        this.startYaw = yaw;
        this.transitionProgress = 1f;
    }

    public void setPitchDirect(float pitch) {
        this.currentPitch = pitch;
        this.targetPitch = pitch;
        this.startPitch = pitch;
        this.transitionProgress = 1f;
    }

    public void setRollDirect(float roll) {
        this.currentRoll = roll;
        this.targetRoll = roll;
        this.startRoll = roll;
        this.transitionProgress = 1f;
    }

    public void setFovDirect(float fov) {
        this.currentFov = fov;
        this.targetFov = fov;
        this.startFov = fov;
        this.transitionProgress = 1f;
    }

    public void setDofDirect(float dof) {
        this.currentDof = dof;
        this.targetDof = dof;
        this.startDof = dof;
        this.transitionProgress = 1f;
    }

    public void setZoomDirect(float zoom) {
        this.currentZoom = zoom;
        this.targetZoom = zoom;
        this.startZoom = zoom;
        this.transitionProgress = 1f;
    }

    // ========== 设置目标值（供 staged 缓冲区使用） ==========

    public void setTargetYaw(float yaw, float duration) {
        this.startYaw = this.currentYaw;
        this.targetYaw = yaw;
        onSetTarget(duration);
    }

    public void setTargetPitch(float pitch, float duration) {
        this.startPitch = this.currentPitch;
        this.targetPitch = pitch;
        onSetTarget(duration);
    }

    public void setTargetRoll(float roll, float duration) {
        this.startRoll = this.currentRoll;
        this.targetRoll = roll;
        onSetTarget(duration);
    }

    public void setTargetFov(float fov, float duration) {
        this.startFov = this.currentFov;
        this.targetFov = fov;
        onSetTarget(duration);
    }

    public void setTargetDof(float dof, float duration) {
        this.startDof = this.currentDof;
        this.targetDof = dof;
        onSetTarget(duration);
    }

    public void setTargetZoom(float zoom, float duration) {
        this.startZoom = this.currentZoom;
        this.targetZoom = zoom;
        onSetTarget(duration);
    }

    /**
     * 统一的过渡初始化逻辑
     * 注意：当多个属性同时设置时，它们共享同一个 transitionDuration/transitionProgress
     * 如果需要独立过渡，后续可拆分为每个属性独立的插值控制
     */
    private void onSetTarget(float duration) {
        this.transitionDuration = duration;
        this.transitionProgress = 0f;

        if (duration <= 0f) {
            // 瞬时跳转：直接将所有当前值同步到目标值
            currentYaw = targetYaw;
            currentPitch = targetPitch;
            currentRoll = targetRoll;
            currentFov = targetFov;
            currentDof = targetDof;
            currentZoom = targetZoom;
            transitionProgress = 1f;
        }
    }

    // ========== tick 驱动（供 staged 缓冲区使用） ==========

    /**
     * 每tick驱动过渡插值
     * <p>
     * 在帧回调驱动模式下，active 缓冲区的属性由 setXxxDirect() 直接设置，
     * 不需要 tick() 驱动。此方法保留用于 staged 缓冲区的过渡动画。
     *
     * @param deltaTime 距离上一tick的时间（秒）
     */
    public void tick(float deltaTime) {
        if (transitionProgress < 1f) {
            transitionProgress = Math.min(1f, transitionProgress + deltaTime / transitionDuration);
            float t = transitionProgress;

            // 角度属性使用角度环绕插值
            currentYaw = MathUtil.lerpAngle(startYaw, targetYaw, t);
            currentPitch = MathUtil.lerpAngle(startPitch, targetPitch, t);
            currentRoll = MathUtil.lerpAngle(startRoll, targetRoll, t);

            // 标量属性使用线性插值
            currentFov = MathUtil.lerp(startFov, targetFov, t);
            currentDof = MathUtil.lerp(startDof, targetDof, t);
            currentZoom = MathUtil.lerp(startZoom, targetZoom, t);
        }
    }

    // ========== 获取当前值（直接返回，无 partialTick 插值） ==========

    /**
     * 🎬 获取当前值（帧回调驱动模式）
     * <p>
     * 每帧已由 setXxxDirect() 精确重算，直接返回即可。
     */
    public float getYaw() { return currentYaw; }
    public float getPitch() { return currentPitch; }
    public float getRoll() { return currentRoll; }
    public float getFov() { return currentFov; }
    public float getDof() { return currentDof; }
    public float getZoom() { return currentZoom; }

    // ========== 硬切换覆盖 ==========

    /**
     * 从另一个 CameraProperties 实例覆盖当前状态（用于硬切换 commitStagedState）
     * <p>
     * 将 staged 缓冲区的状态原子替换到 active 缓冲区。
     *
     * @param source 源实例（通常是 staged 缓冲区）
     */
    public void overrideFrom(CameraProperties source) {
        this.currentYaw = source.currentYaw;
        this.currentPitch = source.currentPitch;
        this.currentRoll = source.currentRoll;
        this.currentFov = source.currentFov;
        this.currentDof = source.currentDof;
        this.currentZoom = source.currentZoom;

        this.targetYaw = source.currentYaw;
        this.targetPitch = source.currentPitch;
        this.targetRoll = source.currentRoll;
        this.targetFov = source.currentFov;
        this.targetDof = source.currentDof;
        this.targetZoom = source.currentZoom;

        this.startYaw = source.currentYaw;
        this.startPitch = source.currentPitch;
        this.startRoll = source.currentRoll;
        this.startFov = source.currentFov;
        this.startDof = source.currentDof;
        this.startZoom = source.currentZoom;

        this.transitionDuration = 0f;
        this.transitionProgress = 1f;
    }

    // ========== 重置 ==========

    /**
     * 重置到默认值
     */
    public void reset() {
        currentYaw = targetYaw = startYaw = 0f;
        currentPitch = targetPitch = startPitch = 0f;
        currentRoll = targetRoll = startRoll = 0f;
        currentFov = targetFov = startFov = DEFAULT_FOV;
        currentDof = targetDof = startDof = DEFAULT_DOF;
        currentZoom = targetZoom = startZoom = DEFAULT_ZOOM;
        transitionDuration = 0f;
        transitionProgress = 1f;
    }

}
