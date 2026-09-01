package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.core.SectionPos;

import java.util.UUID;

/**
 * ChunkMap 虚拟相机中心访问接口。
 * <p>
 * 由 {@code ChunkMapCameraMixin} 实现；外部代码通过 {@code (CameraVirtualCenterAccess) chunkMap}
 * 设置/清除某个玩家当前生效的相机 section。
 */
public interface CameraVirtualCenterAccess {

    /** 设置某玩家的虚拟相机 section；之后原版 ChunkMap 差集围绕该中心运转。 */
    void immersiveCinematics$setCameraSection(UUID playerId, SectionPos section);

    /** 清除某玩家的虚拟相机 section；下一次 move 会以玩家真实位置为新中心。 */
    void immersiveCinematics$clearCameraSection(UUID playerId);
}
