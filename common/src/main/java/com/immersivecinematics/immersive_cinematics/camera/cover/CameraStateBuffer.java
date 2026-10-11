package com.immersivecinematics.immersive_cinematics.camera.cover;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import net.minecraft.world.phys.Vec3;

/**
 * 覆盖链的唯一工作区（预分配、跨帧复用）：Base 层状态的就地读写 + 统一快照产出。
 *
 * 字段口径：位置 = 世界坐标（方块）{@code double}×3；朝向 / 光学 = yaw / pitch / roll（度）、
 * fov（度）、zoom（倍率）{@code float}×5。
 * 数值契约：本类不做 clamp / normalize / 角度环绕——链上原样传递（钳制只存在于渲染边界）。
 * 分配契约：{@link #set(CameraState)} 零分配；{@link #toSnapshot()} 是覆盖链的唯一分配点
 * （每帧 1 份全局快照；位置 {@link Vec3} 在分量不变时复用 provider 快照里的那一个，不额外新建）。
 * 线程契约：仅客户端主线程，跨帧复用（同一时刻只由链持有者使用）。
 */
public final class CameraStateBuffer {

    /** 位置：世界坐标（方块）。 */
    double px, py, pz;

    /** 朝向 / 光学：yaw / pitch / roll / fov（度）、zoom（倍率）。 */
    float yaw, pitch, roll, fov, zoom;

    /** 与 px/py/pz 同值的 Vec3（快照产出复用；分量不一致时重建）。 */
    private Vec3 positionCache;

    /** 把一份六参数快照读进工作区（零分配）。 */
    public void set(CameraState state) {
        Vec3 position = state.position();
        px = position.x;
        py = position.y;
        pz = position.z;
        yaw = state.yaw();
        pitch = state.pitch();
        roll = state.roll();
        fov = state.fov();
        zoom = state.zoom();
        positionCache = position;
    }

    /** 产出不可变快照（覆盖链唯一分配点）。 */
    public CameraState toSnapshot() {
        Vec3 position = positionCache;
        if (position == null || position.x != px || position.y != py || position.z != pz) {
            position = new Vec3(px, py, pz);
            positionCache = position;
        }
        return new CameraState(position, yaw, pitch, roll, fov, zoom);
    }
}
