package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** 相机位置上报（C2S，默认 20 tick 一次）：滑动窗口中心的相机区块坐标（方块坐标） */
public class C2SPreloadPositionPacket implements CinematicC2SPacket {

    private final int x;
    private final int z;
    private final float yaw;
    private final boolean cameraMode;

    public C2SPreloadPositionPacket(int x, int z, float yaw, boolean cameraMode) {
        this.x = x;
        this.z = z;
        this.yaw = yaw;
        this.cameraMode = cameraMode;
    }

    public C2SPreloadPositionPacket(FriendlyByteBuf buf) {
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.yaw = buf.readFloat();
        this.cameraMode = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeFloat(yaw);
        buf.writeBoolean(cameraMode);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（ticket 变更/发包必须主线程）
        ChunkPreloadManager.INSTANCE.handlePosition(player, x, z, yaw, cameraMode);
    }
}
