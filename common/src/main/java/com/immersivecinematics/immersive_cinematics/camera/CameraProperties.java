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

    /**
     * 单个动画属性的过渡状态跟踪
     * <p>
     * 每个属性（yaw/pitch/roll/fov/dof/zoom）拥有独立的过渡进度，
     * 不再共享一个 transitionProgress，避免设置一个属性时错误地重置其他属性的过渡。
     */
    private static class AnimValue {
        float current, target, start;
        float duration;
        float progress = 1f; // 0~1, 1 = 已完成

        /** 直接设置值（瞬移，无过渡） */
        void setDirect(float v) {
            current = target = start = v;
            progress = 1f;
        }

        /** 设置目标值并启动过渡 */
        void setTarget(float v, float dur) {
            start = current;
            target = v;
            if (dur <= 0f) {
                current = v;
                progress = 1f;
            } else {
                duration = dur;
                progress = 0f;
            }
        }

        boolean isAnimating() {
            return progress < 1f;
        }
    }

    // --- 六个动画属性，各自独立跟踪过渡状态 ---
    private final AnimValue yaw = new AnimValue();
    private final AnimValue pitch = new AnimValue();
    private final AnimValue roll = new AnimValue();
    private final AnimValue fov = new AnimValue();
    private final AnimValue dof = new AnimValue();
    private final AnimValue zoom = new AnimValue();

    {
        // 初始化 fov 和 zoom 的默认值（需在构造后执行）
        fov.setDirect(DEFAULT_FOV);
        zoom.setDirect(DEFAULT_ZOOM);
    }

    // ========== 🎬 直接设置方法（帧回调驱动模式） ==========

    /** 🎬 批量设置所有属性（帧回调驱动模式，CameraTrackPlayer 使用） */
    public void setAllDirect(float yaw, float pitch, float roll, float fov, float zoom, float dof) {
        this.yaw.setDirect(yaw);
        this.pitch.setDirect(pitch);
        this.roll.setDirect(roll);
        this.fov.setDirect(fov);
        this.zoom.setDirect(zoom);
        this.dof.setDirect(dof);
    }

    /** 🎬 直接设置偏航角 */
    public void setYawDirect(float v) { yaw.setDirect(v); }

    /** 🎬 直接设置俯仰角 */
    public void setPitchDirect(float v) { pitch.setDirect(v); }

    /** 🎬 直接设置翻滚角 */
    public void setRollDirect(float v) { roll.setDirect(v); }

    /** 🎬 直接设置视场角 */
    public void setFovDirect(float v) { fov.setDirect(v); }

    /** 🎬 直接设置景深 */
    public void setDofDirect(float v) { dof.setDirect(v); }

    /** 🎬 直接设置缩放 */
    public void setZoomDirect(float v) { zoom.setDirect(v); }

    // ========== 设置目标值（供 staged 缓冲区使用） ==========

    /** 设置目标偏航角与过渡时长 */
    public void setTargetYaw(float v, float duration) { yaw.setTarget(v, duration); }

    /** 设置目标俯仰角与过渡时长 */
    public void setTargetPitch(float v, float duration) { pitch.setTarget(v, duration); }

    /** 设置目标翻滚角与过渡时长 */
    public void setTargetRoll(float v, float duration) { roll.setTarget(v, duration); }

    /** 设置目标视场角与过渡时长 */
    public void setTargetFov(float v, float duration) { fov.setTarget(v, duration); }

    /** 设置目标景深与过渡时长 */
    public void setTargetDof(float v, float duration) { dof.setTarget(v, duration); }

    /** 设置目标缩放与过渡时长 */
    public void setTargetZoom(float v, float duration) { zoom.setTarget(v, duration); }

    // ========== tick 驱动（供 staged 缓冲区使用） ==========

    /**
     * 每tick驱动过渡插值
     * <p>
     * 每个属性独立推进过渡进度，互不影响。
     * 角度属性使用角度环绕插值（lerpAngle），标量属性使用线性插值（lerp）。
     *
     * @param deltaTime 距离上一tick的时间（秒）
     */
    public void tick(float deltaTime) {
        tickAngle(yaw, deltaTime);
        tickAngle(pitch, deltaTime);
        tickAngle(roll, deltaTime);
        tickScalar(fov, deltaTime);
        tickScalar(dof, deltaTime);
        tickScalar(zoom, deltaTime);
    }

    private static void tickAngle(AnimValue v, float dt) {
        if (v.progress >= 1f) return;
        v.progress = Math.min(1f, v.progress + dt / v.duration);
        v.current = MathUtil.lerpAngle(v.start, v.target, v.progress);
    }

    private static void tickScalar(AnimValue v, float dt) {
        if (v.progress >= 1f) return;
        v.progress = Math.min(1f, v.progress + dt / v.duration);
        v.current = MathUtil.lerp(v.start, v.target, v.progress);
    }

    // ========== 获取当前值（直接返回，无 partialTick 插值） ==========

    /** 🎬 获取当前偏航角 */
    public float getYaw() { return yaw.current; }

    /** 🎬 获取当前俯仰角 */
    public float getPitch() { return pitch.current; }

    /** 🎬 获取当前翻滚角 */
    public float getRoll() { return roll.current; }

    /** 🎬 获取当前视场角 */
    public float getFov() { return fov.current; }

    /** 🎬 获取当前景深 */
    public float getDof() { return dof.current; }

    /** 🎬 获取当前缩放 */
    public float getZoom() { return zoom.current; }

    // ========== 硬切换覆盖 ==========

    /**
     * 从另一个 CameraProperties 实例覆盖当前状态（用于硬切换 commitStagedState）
     * <p>
     * 将 staged 缓冲区的状态原子替换到 active 缓冲区。
     *
     * @param source 源实例（通常是 staged 缓冲区）
     */
    public void overrideFrom(CameraProperties source) {
        yaw.setDirect(source.yaw.current);
        pitch.setDirect(source.pitch.current);
        roll.setDirect(source.roll.current);
        fov.setDirect(source.fov.current);
        dof.setDirect(source.dof.current);
        zoom.setDirect(source.zoom.current);
    }

    // ========== 重置 ==========

    /** 重置到默认值 */
    public void reset() {
        yaw.setDirect(0f);
        pitch.setDirect(0f);
        roll.setDirect(0f);
        fov.setDirect(DEFAULT_FOV);
        dof.setDirect(DEFAULT_DOF);
        zoom.setDirect(DEFAULT_ZOOM);
    }

}
