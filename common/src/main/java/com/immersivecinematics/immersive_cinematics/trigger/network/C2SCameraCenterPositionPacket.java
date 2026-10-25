package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 相机中心位置上报（C2S，默认 5 tick 一次；位移 ≥1 格或 cameraMode 翻转时立即发）：
 * 会话 id + 相机眼位（含 y）+ 是否相机模式。服务端只接受当前会话。
 */
public class C2SCameraCenterPositionPacket implements CinematicC2SPacket {

    private final long sessionId;
    private final double x;
    private final double y;
    private final double z;
    private final boolean cameraMode;

    public C2SCameraCenterPositionPacket(long sessionId, double x, double y, double z, boolean cameraMode) {
        this.sessionId = sessionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.cameraMode = cameraMode;
    }

    public C2SCameraCenterPositionPacket(FriendlyByteBuf buf) {
        this.sessionId = buf.readLong();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.cameraMode = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(sessionId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeBoolean(cameraMode);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（ticket 变更/发包必须主线程）
        try {
            CameraCenterManager.INSTANCE.handlePosition(player, sessionId, x, y, z, cameraMode);
        } catch (RuntimeException e) {
            // 半更新状态（虚拟中心 / 锚点 / PlayerState）必须释放，异常继续交给 PacketGuard 记录
            CameraCenterManager.INSTANCE.releaseOnFailure(player);
            throw e;
        }
    }
}
