package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.Config;
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
    private static final float DEFAULT_ROLL = 0.0f;
    private static final float DEFAULT_DOF = 0.0f;

    /**
     * 获取配置的默认 FOV
     * <p>
     * 使用 Config.defaultFov，如果 Config 尚未加载则回退到 70.0f。
     */
    private static float getDefaultFov() {
        try {
            return (float) Config.defaultFov;
        } catch (Exception e) {
            return 70.0f; // Config 未加载时的回退值
        }
    }

    /**
     * 获取配置的默认 Zoom
     * <p>
     * 使用 Config.defaultZoom，如果 Config 尚未加载则回退到 1.0f。
     */
    private static float getDefaultZoom() {
        try {
            return (float) Config.defaultZoom;
        } catch (Exception e) {
            return 1.0f; // Config 未加载时的回退值
        }
    }

    // --- 当前值 ---
    private float currentYaw = 0f;
    private float currentPitch = 0f;
    private float currentRoll = 0f;
    private float currentFov = getDefaultFov();
    private float currentDof = DEFAULT_DOF;
    private float currentZoom = getDefaultZoom();

    // --- 目标值（供 staged 缓冲区过渡插值使用） ---
    private float targetYaw = 0f;
    private float targetPitch = 0f;
    private float targetRoll = 0f;
    private float targetFov = getDefaultFov();
    private float targetDof = DEFAULT_DOF;
    private float targetZoom = getDefaultZoom();

    // --- 插值起点（供 staged 缓冲区过渡插值使用） ---
    private float startYaw = 0f;
    private float startPitch = 0f;
    private float startRoll = 0f;
    private float startFov = getDefaultFov();
    private float startDof = DEFAULT_DOF;
    private float startZoom = getDefaultZoom();

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

    /**
     * 设置目标偏航角
     * <p>
     * 当 duration <= 0 时，只跳转当前属性（currentYaw），不影响其他正在过渡中的属性。
     *
     * @param yaw     目标偏航角
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetYaw(float yaw, float duration) {
        this.startYaw = this.currentYaw;
        this.targetYaw = yaw;
        if (duration <= 0f) {
            this.currentYaw = yaw;
            this.transitionProgress = 1f;
        } else {
            onSetTarget(duration);
        }
    }

    /**
     * 设置目标俯仰角
     * <p>
     * 当 duration <= 0 时，只跳转当前属性（currentPitch），不影响其他正在过渡中的属性。
     *
     * @param pitch    目标俯仰角
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetPitch(float pitch, float duration) {
        this.startPitch = this.currentPitch;
        this.targetPitch = pitch;
        if (duration <= 0f) {
            this.currentPitch = pitch;
            this.transitionProgress = 1f;
        } else {
            onSetTarget(duration);
        }
    }

    /**
     * 设置目标翻滚角
     * <p>
     * 当 duration <= 0 时，只跳转当前属性（currentRoll），不影响其他正在过渡中的属性。
     *
     * @param roll     目标翻滚角
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetRoll(float roll, float duration) {
        this.startRoll = this.currentRoll;
        this.targetRoll = roll;
        if (duration <= 0f) {
            this.currentRoll = roll;
            this.transitionProgress = 1f;
        } else {
            onSetTarget(duration);
        }
    }

    /**
     * 设置目标视场角
     * <p>
     * 当 duration <= 0 时，只跳转当前属性（currentFov），不影响其他正在过渡中的属性。
     *
     * @param fov      目标视场角
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetFov(float fov, float duration) {
        this.startFov = this.currentFov;
        this.targetFov = fov;
        if (duration <= 0f) {
            this.currentFov = fov;
            this.transitionProgress = 1f;
        } else {
            onSetTarget(duration);
        }
    }

    /**
     * 设置目标景深
     * <p>
     * 当 duration <= 0 时，只跳转当前属性（currentDof），不影响其他正在过渡中的属性。
     *
     * @param dof      目标景深
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetDof(float dof, float duration) {
        this.startDof = this.currentDof;
        this.targetDof = dof;
        if (duration <= 0f) {
            this.currentDof = dof;
            this.transitionProgress = 1f;
        } else {
            onSetTarget(duration);
        }
    }

    /**
     * 设置目标缩放
     * <p>
     * 当 duration <= 0 时，只跳转当前属性（currentZoom），不影响其他正在过渡中的属性。
     *
     * @param zoom     目标缩放
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void setTargetZoom(float zoom, float duration) {
        this.startZoom = this.currentZoom;
        this.targetZoom = zoom;
        if (duration <= 0f) {
            this.currentZoom = zoom;
            this.transitionProgress = 1f;
        } else {
            onSetTarget(duration);
        }
    }

    /**
     * 统一的过渡初始化逻辑
     * <p>
     * 注意：当多个属性同时设置时，它们共享同一个 transitionDuration/transitionProgress。
     * 这是已知限制，长期方案为每个属性独立插值控制。
     * <p>
     * 瞬时跳转逻辑已移至各 setTargetXxx() 方法中，只跳转调用者指定的属性，
     * 不再强制将所有属性同步到目标值。
     */
    private void onSetTarget(float duration) {
        this.transitionDuration = duration;
        this.transitionProgress = 0f;
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
        currentFov = targetFov = startFov = getDefaultFov();
        currentDof = targetDof = startDof = DEFAULT_DOF;
        currentZoom = targetZoom = startZoom = getDefaultZoom();
        transitionDuration = 0f;
        transitionProgress = 1f;
    }

}
