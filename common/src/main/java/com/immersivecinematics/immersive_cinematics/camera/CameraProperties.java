package com.immersivecinematics.immersive_cinematics.camera;

/**
 * 相机朝向与光学写入缓冲（六参数之 yaw / pitch / roll / fov / zoom）：只保存当前值，无插值、无过渡。
 *
 * 单位：yaw / pitch / roll / fov 为度，zoom 为倍率。缺省：yaw / pitch / roll = 0、fov = 70、zoom = 1；
 * 越界值不在此处钳制（钳制在渲染边界）。写入 = 覆盖当前值。
 * 仅包内可见：它是全局写入缓冲的组成，读写入口见 {@code CameraStateHolder}。线程契约：仅客户端主线程。
 */
final class CameraProperties {

    /** 缺省视场角（度）。 */
    private static final float DEFAULT_FOV = 70.0f;

    /** 缺省缩放（倍率）。 */
    private static final float DEFAULT_ZOOM = 1.0f;

    /** 单个属性的当前值。 */
    private static class AnimValue {
        float current;

        void setDirect(float v) {
            current = v;
        }
    }

    private final AnimValue yaw = new AnimValue();
    private final AnimValue pitch = new AnimValue();
    private final AnimValue roll = new AnimValue();
    private final AnimValue fov = new AnimValue();
    private final AnimValue zoom = new AnimValue();

    CameraProperties() {
        fov.setDirect(DEFAULT_FOV);
        zoom.setDirect(DEFAULT_ZOOM);
    }

    /** 批量覆盖五个属性。 */
    void setAllDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        this.yaw.setDirect(yaw);
        this.pitch.setDirect(pitch);
        this.roll.setDirect(roll);
        this.fov.setDirect(fov);
        this.zoom.setDirect(zoom);
    }

    /** 覆盖 yaw（度）。 */
    void setYawDirect(float v) { yaw.setDirect(v); }

    /** 覆盖 pitch（度）。 */
    void setPitchDirect(float v) { pitch.setDirect(v); }

    /** 当前 yaw（度）。 */
    float getYaw() { return yaw.current; }

    /** 当前 pitch（度）。 */
    float getPitch() { return pitch.current; }

    /** 当前 roll（度）。 */
    float getRoll() { return roll.current; }

    /** 当前 fov（度）。 */
    float getFov() { return fov.current; }

    /** 当前 zoom（倍率）。 */
    float getZoom() { return zoom.current; }

    /** 复位到缺省值（yaw / pitch / roll = 0、fov = 70、zoom = 1）。 */
    void reset() {
        yaw.setDirect(0f);
        pitch.setDirect(0f);
        roll.setDirect(0f);
        fov.setDirect(DEFAULT_FOV);
        zoom.setDirect(DEFAULT_ZOOM);
    }
}
