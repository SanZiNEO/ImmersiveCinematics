package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** 相机中心位置上报（C2S，默认 20 tick 一次）：中心方块坐标 + 是否相机模式 */
public class C2SCameraCenterPositionPacket implements CinematicC2SPacket {

    private final int x;
    private final int z;
    private final boolean cameraMode;

    public C2SCameraCenterPositionPacket(int x, int z, boolean cameraMode) {
        this.x = x;
        this.z = z;
        this.cameraMode = cameraMode;
    }

    public C2SCameraCenterPositionPacket(FriendlyByteBuf buf) {
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.cameraMode = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeBoolean(cameraMode);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（ticket 变更/发包必须主线程）
        try {
            CameraCenterManager.INSTANCE.handlePosition(player, x, z, cameraMode);
        } catch (RuntimeException e) {
            // 半更新状态（虚拟中心 / 锚点 / PlayerState）必须释放，异常继续交给 PacketGuard 记录
            CameraCenterManager.INSTANCE.releaseOnFailure(player);
            throw e;
        }
    }
}
