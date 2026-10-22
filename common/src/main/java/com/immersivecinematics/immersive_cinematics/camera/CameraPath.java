package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.world.phys.Vec3;

/**
 * 相机位置写入缓冲（六参数之 x / y / z）：只保存当前值，无插值、无过渡。
 *
 * 位置 = 世界坐标（方块）；缺省 {@link Vec3#ZERO}。写入 = 覆盖当前值，读取即当前值。
 * 仅包内可见：它是全局写入缓冲的组成，读写入口见 {@code CameraStateHolder}。线程契约：仅客户端主线程。
 */
final class CameraPath {

    /** 当前值（世界坐标，方块）。 */
    private Vec3 currentPosition = Vec3.ZERO;

    /** 覆盖当前值。 */
    void setPositionDirect(Vec3 pos) {
        this.currentPosition = pos;
    }

    /** 当前值。 */
    Vec3 getPosition() {
        return currentPosition;
    }

    /** 复位到原点。 */
    void reset() {
        currentPosition = Vec3.ZERO;
    }
}
