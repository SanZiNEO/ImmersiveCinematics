package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.world.phys.Vec3;

/**
 * 相机统一只读状态 — 六参数不可变快照。
 * <p>
 * 六参数：位置 {@code x/y/z}（{@link #position()}）+ 朝向 {@code yaw/pitch}
 * + 附加 {@code roll/fov/zoom}。这是渲染侧（Mixin）唯一允许读取的相机状态形态，
 * 读侧不再直接接触 {@link CameraPath} / {@link CameraProperties} 这两个内部可变对象。
 * <p>
 * 语义与所有权：
 * <ul>
 *   <li>不可变快照 — record 的六个分量在构造后不可更改；</li>
 *   <li>由 {@link CameraManager} 在每帧更新末尾从内部状态（{@code activePath} /
 *       {@code activeProperties} 的 current 值）生成并缓存；主相机替换链退役后读侧为
 *       {@code AudioListenerController}（听者位置/听者相机代理）、{@code PreloadRequester}
 *       （预加载中心）、{@code WebPreviewScreen}（预览 HUD）；</li>
 *   <li><b>不活跃的表示是 {@code null} 快照</b>（{@code CameraManager.getCameraState()}
 *       在无活跃相机时返回 {@code null}），而不是“零值 CameraState”——
 *       这样读侧可原样保留各自的 isActive / 回落 guard，边界行为不变；</li>
 *   <li>因此 {@link #position()} 在实例存在时恒非 {@code null}。</li>
 * </ul>
 * <p>
 * 应用点：lane 相机（{@code LaneRenderer} 逐 lane 渲染）用自己的 {@code CameraLane} 快照
 * （同源同值），不再经本快照；主相机不再被接管（主画面 = 全屏 lane 特例）。
 */
public record CameraState(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
}
