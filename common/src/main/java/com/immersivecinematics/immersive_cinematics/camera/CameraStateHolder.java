package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import net.minecraft.world.phys.Vec3;

/**
 * 全局相机状态持有者：写入缓冲（{@link CameraPath} / {@link CameraProperties}）+ 当前值镜像 + 统一只读快照。
 *
 * 写入缓冲是「顶层实例顶层活跃 clip 当前值」的唯一落点；{@link #current()} 是它的不可变镜像
 * （每次写入同步），覆盖链上各 Base 层据此取自身状态。脚本导演写入同时带上该片段的
 * {@code suppress_modifiers} 标志（{@link #suppressesModifiers()}），供脚本导演 Base 层跳过 Modifier 链。
 * 快照 {@link CameraState} 由调用方置入（覆盖链输出），无活跃相机时为 {@code null}（读侧据此回落）。
 *
 * 刷新时机由调用方（{@code CameraManager}）决定——每帧驱动末尾、停用 / 重启、直写入口；
 * 同一帧内多次读取得到同一份快照。缓冲写入不隐含刷新：写入只改缓冲与镜像，
 * 要求立即对读侧可见的入口须紧接着刷新快照。
 */
final class CameraStateHolder {

    /** 位置写入缓冲（六参数之 x / y / z）。 */
    private final CameraPath path = new CameraPath();

    /** 朝向与光学写入缓冲（六参数之 yaw / pitch / roll / fov / zoom）。 */
    private final CameraProperties properties = new CameraProperties();

    /** 写入缓冲的当前值（不可变镜像，随每次写入同步）。 */
    private CameraState current;

    /** 统一快照；无活跃相机时为 {@code null}。 */
    private CameraState snapshot = null;

    /** 当前值是否要求跳过 Modifier 链（随脚本导演写入同步；缺省 {@code false}）。 */
    private boolean suppressModifiers = false;

    CameraStateHolder() {
        current = readBuffer();
    }

    /** 位置写入缓冲（门面读数 / 复位用；脚本导演写入经 {@link #setScriptState}）。 */
    CameraPath path() {
        return path;
    }

    /** 朝向与光学写入缓冲（门面读数 / 复位用；脚本导演写入经 {@link #setScriptState}）。 */
    CameraProperties properties() {
        return properties;
    }

    /** 写入缓冲的当前值（不可变镜像）。 */
    CameraState current() {
        return current;
    }

    /** 直写六参数（含位置）：只写缓冲与镜像，快照由调用方刷新。 */
    void setDirect(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
        path.setPositionDirect(position);
        properties.setAllDirect(yaw, pitch, roll, fov, zoom);
        current = new CameraState(position, yaw, pitch, roll, fov, zoom);
    }

    /** 直写五参数（位置不动，编辑器直控）：只写缓冲与镜像，快照由调用方刷新。 */
    void setDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        properties.setAllDirect(yaw, pitch, roll, fov, zoom);
        current = new CameraState(path.getPosition(), yaw, pitch, roll, fov, zoom);
    }

    /** 脚本导演写侧（顶层 clip 本帧六参数）：写入缓冲 + 镜像复用传入快照，不新建 record。 */
    void setScriptState(CameraState state, boolean suppressModifiers) {
        path.setPositionDirect(state.position());
        properties.setAllDirect(state.yaw(), state.pitch(), state.roll(), state.fov(), state.zoom());
        current = state;
        this.suppressModifiers = suppressModifiers;
    }

    /** 开播起始视角：位置与 yaw/pitch 初始化到玩家；roll / fov / zoom 保持缓冲现值。 */
    void setInitialView(Vec3 position, float yaw, float pitch) {
        path.setPositionDirect(position);
        properties.setYawDirect(yaw);
        properties.setPitchDirect(pitch);
        current = new CameraState(position, yaw, pitch,
                properties.getRoll(), properties.getFov(), properties.getZoom());
    }

    /** 统一快照；无活跃相机时为 {@code null}。 */
    CameraState snapshot() {
        return snapshot;
    }

    /** 当前值是否要求跳过 Modifier 链（写入方 = 顶层片段 {@code suppress_modifiers}；缺省 {@code false}）。 */
    boolean suppressesModifiers() {
        return suppressModifiers;
    }

    /** 置入统一快照（值 = 覆盖链输出）；{@code null} = 无活跃相机。 */
    void setSnapshot(CameraState snapshot) {
        this.snapshot = snapshot;
    }

    /** 缓冲复位到缺省（位置回原点、五参数回缺省值）；镜像随缓冲重读；快照不动（由调用方刷新）。 */
    void reset() {
        path.reset();
        properties.reset();
        current = readBuffer();
        suppressModifiers = false;
    }

    /** 从缓冲重建镜像（缺省值 / 复位后取值；每帧路径不走这里）。 */
    private CameraState readBuffer() {
        return new CameraState(path.getPosition(),
                properties.getYaw(), properties.getPitch(),
                properties.getRoll(), properties.getFov(), properties.getZoom());
    }
}
