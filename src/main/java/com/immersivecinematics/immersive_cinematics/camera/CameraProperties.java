package com.immersivecinematics.immersive_cinematics.camera;

/**
 * 相机属性控制器 — 管理相机的所有自身属性
 * 包含：朝向（yaw, pitch, roll）和 光学特征（FOV, DOF, Zoom）
 * 完全不知道 CameraPath 的存在
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

    // --- 上一tick的值（用于帧级 partialTick 插值） ---
    private float previousYaw = 0f;
    private float previousPitch = 0f;
    private float previousRoll = 0f;
    private float previousFov = DEFAULT_FOV;
    private float previousDof = DEFAULT_DOF;
    private float previousZoom = DEFAULT_ZOOM;

    // --- 目标值 ---
    private float targetYaw = 0f;
    private float targetPitch = 0f;
    private float targetRoll = 0f;
    private float targetFov = DEFAULT_FOV;
    private float targetDof = DEFAULT_DOF;
    private float targetZoom = DEFAULT_ZOOM;

    // --- 插值起点 ---
    private float startYaw = 0f;
    private float startPitch = 0f;
    private float startRoll = 0f;
    private float startFov = DEFAULT_FOV;
    private float startDof = DEFAULT_DOF;
    private float startZoom = DEFAULT_ZOOM;

    // --- 插值控制 ---
    private float transitionDuration = 0f;   // 过渡时长（秒），0 = 瞬时
    private float transitionProgress = 1f;   // 0~1，1 = 已完成

    // ========== 设置目标值 ==========

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

    // ========== tick 驱动 ==========

    /**
     * 每tick驱动插值
     * @param deltaTime 距离上一tick的时间（秒）
     */
    public void tick(float deltaTime) {
        if (transitionProgress < 1f) {
            transitionProgress = Math.min(1f, transitionProgress + deltaTime / transitionDuration);
            float t = transitionProgress;

            // 角度属性使用角度环绕插值
            currentYaw = lerpAngle(startYaw, targetYaw, t);
            currentPitch = lerpAngle(startPitch, targetPitch, t);
            currentRoll = lerpAngle(startRoll, targetRoll, t);

            // 标量属性使用线性插值
            currentFov = lerp(startFov, targetFov, t);
            currentDof = lerp(startDof, targetDof, t);
            currentZoom = lerp(startZoom, targetZoom, t);
        }
    }

    // ========== 获取当前值 ==========

    public float getYaw() { return currentYaw; }
    public float getPitch() { return currentPitch; }
    public float getRoll() { return currentRoll; }
    public float getFov() { return currentFov; }
    public float getDof() { return currentDof; }
    public float getZoom() { return currentZoom; }

    // ========== partialTick 插值读取（给 Mixin 渲染帧用） ==========

    /**
     * 每tick开始时保存当前值为"上一tick值"，供渲染帧 partialTick 插值使用。
     * 必须在 CameraManager.tick() 开头、所有 setTargetXxx 调用之前执行。
     */
    public void savePreviousTick() {
        this.previousYaw = this.currentYaw;
        this.previousPitch = this.currentPitch;
        this.previousRoll = this.currentRoll;
        this.previousFov = this.currentFov;
        this.previousDof = this.currentDof;
        this.previousZoom = this.currentZoom;
    }

    /** 获取 partialTick 插值后的 yaw（角度环绕） */
    public float getYawInterpolated(float partialTick) {
        return lerpAngle(previousYaw, currentYaw, partialTick);
    }

    /** 获取 partialTick 插值后的 pitch（角度环绕） */
    public float getPitchInterpolated(float partialTick) {
        return lerpAngle(previousPitch, currentPitch, partialTick);
    }

    /** 获取 partialTick 插值后的 fov（线性插值） */
    public float getFovInterpolated(float partialTick) {
        return lerp(previousFov, currentFov, partialTick);
    }

    /** 获取 partialTick 插值后的 roll（角度环绕） */
    public float getRollInterpolated(float partialTick) {
        return lerpAngle(previousRoll, currentRoll, partialTick);
    }

    /** 获取 partialTick 插值后的 zoom（线性插值） */
    public float getZoomInterpolated(float partialTick) {
        return lerp(previousZoom, currentZoom, partialTick);
    }

    // ========== 硬切换覆盖 ==========

    /**
     * 从另一个 CameraProperties 实例覆盖当前状态（用于硬切换 commitStagedState）
     * <p>
     * 关键：所有 previous* 也被设为 source.current*，
     * 确保 partialTick 插值结果恒等于 source 的当前值，消除1tick的"飞过去"过渡。
     *
     * @param source 源实例（通常是 staged 缓冲区）
     */
    public void overrideFrom(CameraProperties source) {
        // current = source.current
        this.currentYaw = source.currentYaw;
        this.currentPitch = source.currentPitch;
        this.currentRoll = source.currentRoll;
        this.currentFov = source.currentFov;
        this.currentDof = source.currentDof;
        this.currentZoom = source.currentZoom;

        // 关键：previous = current，消除 partialTick 插值
        this.previousYaw = source.currentYaw;
        this.previousPitch = source.currentPitch;
        this.previousRoll = source.currentRoll;
        this.previousFov = source.currentFov;
        this.previousDof = source.currentDof;
        this.previousZoom = source.currentZoom;

        // target = current，start = current，确保后续 tick 不会产生残留过渡
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
        currentYaw = targetYaw = startYaw = previousYaw = 0f;
        currentPitch = targetPitch = startPitch = previousPitch = 0f;
        currentRoll = targetRoll = startRoll = previousRoll = 0f;
        currentFov = targetFov = startFov = previousFov = DEFAULT_FOV;
        currentDof = targetDof = startDof = previousDof = DEFAULT_DOF;
        currentZoom = targetZoom = startZoom = previousZoom = DEFAULT_ZOOM;
        transitionDuration = 0f;
        transitionProgress = 1f;
    }

    // ========== 插值工具 ==========

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * 角度环绕插值（处理 -180° ~ 180° 的环绕）
     */
    private static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return from + diff * t;
    }
}
