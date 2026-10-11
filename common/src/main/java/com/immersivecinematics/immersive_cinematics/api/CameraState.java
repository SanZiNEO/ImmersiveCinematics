package com.immersivecinematics.immersive_cinematics.api;

import net.minecraft.world.phys.Vec3;

/**
 * 相机统一只读状态：六参数不可变快照。
 *
 * 六参数 = 位置 {@code x/y/z}（世界坐标，方块）+ 朝向 {@code yaw/pitch}（度）+ 附加 {@code roll}（度）、
 * {@code fov}（度）、{@code zoom}（倍率）。这是渲染侧唯一允许读取的相机状态形态。
 * 不活跃的表示 = {@code null} 快照（{@link CameraApi#getCameraState()} 在无活跃相机时返回 {@code null}），
 * 读侧据此回落玩家相机；因此 {@link #position()} 在快照存在时恒非 {@code null}。
 * 分配契约：相机状态链每帧可多次产出（帧末快照 + 直写入口穿透刷新），同帧内多次读取同一份；快照跨帧不可变。
 * 线程契约：仅客户端主线程（tick 与渲染同线程）产生与读取。
 */
public record CameraState(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
}
