package com.immersivecinematics.immersive_cinematics.camera.cover;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import net.minecraft.world.phys.Vec3;

/**
 * 覆盖链的工作区（预分配、跨帧复用）：Base 状态与 Modifier 值的就地读写 + 统一快照产出。
 *
 * 字段口径：位置 = 世界坐标（方块）{@code double}×3；朝向 / 光学 = yaw / pitch / roll（度）、
 * fov（度）、zoom（倍率）{@code float}×5。
 * 数值契约：本类不做 clamp / normalize / 角度环绕——链上原样传递（钳制只存在于渲染边界）。
 * 分配契约：{@link #set(CameraState)} 与 {@link #compose(int, ComposeMode, CameraStateBuffer)} 零分配；
 * {@link #toSnapshot()} 是覆盖链的唯一分配点（每帧可多次产出：帧末快照 + 直写入口穿透刷新；位置 {@link Vec3} 在分量不变时复用
 * provider 快照里的那一个，被 Modifier 改动时随快照新建一个）。
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

    /**
     * 就地合成：把 {@code value} 的掩码内通道并入本工作区，掩码外通道不动（先掩码过滤、再逐通道合成）。
     *
     * 通道口径：{@code position} 一位覆盖 x / y / z 三轴（同一模式）；position 以 double 计算，其余通道
     * 经 double 计算后收窄回 float（两个 float 的加减乘在 double 中精确，结果与 float 直算逐位一致）。
     *
     * @param mask  生效通道位掩码（{@link CameraChannels} 的位）
     * @param mode  合成方式；{@code null} = 逐通道缺省（{@link CameraChannels#defaultMode(int)}）
     * @param value 通道值来源（只读；掩码内通道必须已由调用方写入）
     */
    public void compose(int mask, ComposeMode mode, CameraStateBuffer value) {
        if ((mask & CameraChannels.POSITION) != 0) {
            ComposeMode m = resolve(mode, CameraChannels.POSITION);
            px = m.apply(px, value.px);
            py = m.apply(py, value.py);
            pz = m.apply(pz, value.pz);
        }
        if ((mask & CameraChannels.YAW) != 0) {
            yaw = (float) resolve(mode, CameraChannels.YAW).apply(yaw, value.yaw);
        }
        if ((mask & CameraChannels.PITCH) != 0) {
            pitch = (float) resolve(mode, CameraChannels.PITCH).apply(pitch, value.pitch);
        }
        if ((mask & CameraChannels.ROLL) != 0) {
            roll = (float) resolve(mode, CameraChannels.ROLL).apply(roll, value.roll);
        }
        if ((mask & CameraChannels.FOV) != 0) {
            fov = (float) resolve(mode, CameraChannels.FOV).apply(fov, value.fov);
        }
        if ((mask & CameraChannels.ZOOM) != 0) {
            zoom = (float) resolve(mode, CameraChannels.ZOOM).apply(zoom, value.zoom);
        }
    }

    /** 解析通道合成方式：显式模式优先，否则取该通道缺省。 */
    private static ComposeMode resolve(ComposeMode mode, int channel) {
        return mode != null ? mode : CameraChannels.defaultMode(channel);
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
