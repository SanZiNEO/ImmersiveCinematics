package com.immersivecinematics.immersive_cinematics.camera.transition;

/**
 * 过渡的六参数载体（预分配、跨帧复用）——过渡层只读写本类的原始字段，不装箱 / 不建集合 / 不产出快照。
 *
 * 字段口径：位置 = 世界坐标（方块）{@code double}×3；yaw / pitch / roll（度）、fov（度）、zoom（倍率）
 * {@code float}×5（与 {@code api.CameraState} 同口径，快照由消费方按自己的口径产出）。
 * 分配契约：{@link #set} / {@link #copyFrom} 零分配。线程契约：仅客户端主线程，同一实例只由持有者使用。
 */
public final class TransitionFrame {

    /** 位置 x（世界坐标，方块）。 */
    public double px;

    /** 位置 y（世界坐标，方块）。 */
    public double py;

    /** 位置 z（世界坐标，方块）。 */
    public double pz;

    /** yaw（度）。 */
    public float yaw;

    /** pitch（度）。 */
    public float pitch;

    /** roll（度）。 */
    public float roll;

    /** fov（度）。 */
    public float fov;

    /** zoom（倍率）。 */
    public float zoom;

    public void set(double px, double py, double pz, float yaw, float pitch, float roll, float fov, float zoom) {
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fov = fov;
        this.zoom = zoom;
    }

    public void copyFrom(TransitionFrame other) {
        set(other.px, other.py, other.pz, other.yaw, other.pitch, other.roll, other.fov, other.zoom);
    }
}
