package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.world.phys.Vec3;

/**
 * 全局相机状态持有者：写入缓冲（{@link CameraPath} / {@link CameraProperties}）+ 统一只读快照。
 *
 * 写入缓冲是「顶层实例顶层活跃 clip 当前值」的唯一落点；快照 {@link CameraState} 由
 * {@link #refresh(boolean)} 从缓冲重建，无活跃相机时为 {@code null}（读侧据此回落）。
 *
 * 刷新时机由调用方（{@code CameraManager}）决定——每帧驱动末尾、停用 / 重启、直写入口；
 * 同一帧内多次读取得到同一份快照。缓冲写入不隐含刷新：{@code setDirect} 只写缓冲，
 * 要求立即对读侧可见的入口须紧接着调 {@link #refresh(boolean)}。
 */
final class CameraStateHolder {

    /** 位置写入缓冲（六参数之 x / y / z）。 */
    private final CameraPath path = new CameraPath();

    /** 朝向与光学写入缓冲（六参数之 yaw / pitch / roll / fov / zoom）。 */
    private final CameraProperties properties = new CameraProperties();

    /** 统一快照；无活跃相机时为 {@code null}。 */
    private CameraState snapshot = null;

    /** 位置写入缓冲（供轨道播放器逐帧写入当前值）。 */
    CameraPath path() {
        return path;
    }

    /** 朝向与光学写入缓冲（供轨道播放器逐帧写入当前值）。 */
    CameraProperties properties() {
        return properties;
    }

    /** 直写六参数（含位置）：只写缓冲，快照由 {@link #refresh(boolean)} 重建。 */
    void setDirect(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
        path.setPositionDirect(position);
        properties.setAllDirect(yaw, pitch, roll, fov, zoom);
    }

    /** 直写五参数（位置不动，编辑器直控）：只写缓冲，快照由 {@link #refresh(boolean)} 重建。 */
    void setDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        properties.setAllDirect(yaw, pitch, roll, fov, zoom);
    }

    /** 统一快照；无活跃相机时为 {@code null}。 */
    CameraState snapshot() {
        return snapshot;
    }

    /**
     * 从写入缓冲重建快照。
     *
     * @param active 是否有活跃播放实例；{@code false} = 快照置 {@code null}
     */
    void refresh(boolean active) {
        snapshot = active
                ? new CameraState(path.getPosition(),
                        properties.getYaw(), properties.getPitch(),
                        properties.getRoll(), properties.getFov(), properties.getZoom())
                : null;
    }

    /** 缓冲复位到缺省（位置回原点、五参数回缺省值）；快照不动（由调用方的 refresh 决定）。 */
    void reset() {
        path.reset();
        properties.reset();
    }
}
