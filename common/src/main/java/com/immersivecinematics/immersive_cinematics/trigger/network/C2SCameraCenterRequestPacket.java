package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 相机中心同步请求（C2S）：模式 START(0) / END(1)，携带脚本 id、会话 id、相机眼位（方块，含 y）
 * 与是否相机模式。END 复用本包，sessionId 必须与 START 相同。
 */
public class C2SCameraCenterRequestPacket implements CinematicC2SPacket {

    public static final int MODE_START = 0;
    public static final int MODE_END = 1;

    private final int mode;
    private final String scriptId;
    private final long sessionId;
    private final double x;
    private final double y;
    private final double z;
    private final boolean cameraMode;

    public C2SCameraCenterRequestPacket(int mode, String scriptId, long sessionId,
                                        double x, double y, double z, boolean cameraMode) {
        this.mode = mode;
        this.scriptId = scriptId;
        this.sessionId = sessionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.cameraMode = cameraMode;
    }

    public C2SCameraCenterRequestPacket(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        this.scriptId = buf.readUtf();
        this.sessionId = buf.readLong();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.cameraMode = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        buf.writeUtf(scriptId);
        buf.writeLong(sessionId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeBoolean(cameraMode);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（ChunkMap.move / ticket 变更必须主线程）
        try {
            CameraCenterManager.INSTANCE.handleRequest(player, mode, scriptId, sessionId, x, y, z, cameraMode);
        } catch (RuntimeException e) {
            // 半更新状态（虚拟中心 / 锚点 / PlayerState）必须释放，异常继续交给 PacketGuard 记录
            CameraCenterManager.INSTANCE.releaseOnFailure(player);
            throw e;
        }
    }
}
