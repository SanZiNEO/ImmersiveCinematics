package com.immersivecinematics.immersive_cinematics.api;

/**
 * 相机只读查询面（对外稳定面）：过场是否接管相机、本帧是否有活跃 CAMERA 片段、统一状态快照。
 *
 * 三项都是无副作用查询，值取客户端当前帧的相机状态链输出。
 * 稳定性：0.3.6 内不承诺外部稳定（破坏性变更随模组版本走）；本版本只开放这三个只读方法，
 * 写入口、事件与 Modifier 注册不开放。
 * 线程契约：仅客户端主线程（tick 与渲染同线程）；跨线程调用方须自行 marshal 到主线程。
 * 取得实现：客户端相机门面单例（{@code CameraManager.INSTANCE}）。
 */
public interface CameraApi {

    /** 相机是否被过场接管（= 有活跃播放实例）；{@code false} = 原版相机照常。 */
    boolean isActive();

    /** 全部活跃实例本帧是否有活跃 CAMERA 片段（帧级缓存，同一帧内多次读取一致）。 */
    boolean hasActiveCameraClip();

    /** 统一相机状态快照（本帧最新值）；无活跃相机时为 {@code null}。 */
    CameraState getCameraState();
}
