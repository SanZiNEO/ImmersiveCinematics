package com.immersivecinematics.immersive_cinematics.camera;

/**
 * 相机属性控制器 — 管理相机的所有自身属性
 * 包含：朝向（yaw, pitch, roll）和 光学特征（FOV, Zoom）
 * 完全不知道 CameraPath 的存在
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - 不再使用 partialTick 插值
 * - 每渲染帧由 CameraTrackPlayer.onRenderFrame() 直接设置精确值
 * - getXxx() 直接返回 currentXxx，无插值层
 * <p>
 * 注意：画幅比（aspectRatio）不在此处管理，因为它属于覆盖层系统，
 * 独立于镜头跳转逻辑，由 CameraManager 直接管理。
 */
public class CameraProperties {

    // --- 默认值 ---
    private static final float DEFAULT_FOV = 70.0f;
    private static final float DEFAULT_ROLL = 0.0f;
    private static final float DEFAULT_ZOOM = 1.0f;

    /**
     * 单个动画属性的当前值
     */
    private static class AnimValue {
        float current;

        /** 直接设置值（瞬移，无过渡） */
        void setDirect(float v) {
            current = v;
        }
    }

    // --- 五个动画属性 ---
    private final AnimValue yaw = new AnimValue();
    private final AnimValue pitch = new AnimValue();
    private final AnimValue roll = new AnimValue();
    private final AnimValue fov = new AnimValue();
    private final AnimValue zoom = new AnimValue();

    {
        // 初始化 fov 和 zoom 的默认值（需在构造后执行）
        fov.setDirect(DEFAULT_FOV);
        zoom.setDirect(DEFAULT_ZOOM);
    }

    // ========== 🎬 直接设置方法（帧回调驱动模式） ==========

    /** 🎬 批量设置所有属性（帧回调驱动模式，CameraTrackPlayer 使用） */
    public void setAllDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        this.yaw.setDirect(yaw);
        this.pitch.setDirect(pitch);
        this.roll.setDirect(roll);
        this.fov.setDirect(fov);
        this.zoom.setDirect(zoom);
    }

    /** 🎬 直接设置偏航角 */
    public void setYawDirect(float v) { yaw.setDirect(v); }

    /** 🎬 直接设置俯仰角 */
    public void setPitchDirect(float v) { pitch.setDirect(v); }

    /** 🎬 直接设置翻滚角 */
    public void setRollDirect(float v) { roll.setDirect(v); }

    /** 🎬 直接设置视场角 */
    public void setFovDirect(float v) { fov.setDirect(v); }


    /** 🎬 直接设置缩放 */
    public void setZoomDirect(float v) { zoom.setDirect(v); }

    // ========== 获取当前值（直接返回，无 partialTick 插值） ==========

    /** 🎬 获取当前偏航角 */
    public float getYaw() { return yaw.current; }

    /** 🎬 获取当前俯仰角 */
    public float getPitch() { return pitch.current; }

    /** 🎬 获取当前翻滚角 */
    public float getRoll() { return roll.current; }

    /** 🎬 获取当前视场角 */
    public float getFov() { return fov.current; }


    /** 🎬 获取当前缩放 */
    public float getZoom() { return zoom.current; }

    // ========== 重置 ==========

    /** 重置到默认值 */
    public void reset() {
        yaw.setDirect(0f);
        pitch.setDirect(0f);
        roll.setDirect(0f);
        fov.setDirect(DEFAULT_FOV);
        zoom.setDirect(DEFAULT_ZOOM);
    }

}
