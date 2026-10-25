package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** 相机中心同步请求（C2S）：模式 START(0) / END(1)，携带脚本 id、中心坐标（方块）与是否相机模式 */
public class C2SCameraCenterRequestPacket implements CinematicC2SPacket {

    public static final int MODE_START = 0;
    public static final int MODE_END = 1;

    private final int mode;
    private final String scriptId;
    private final int x;
    private final int z;
    private final boolean cameraMode;

    public C2SCameraCenterRequestPacket(int mode, String scriptId, int x, int z, boolean cameraMode) {
        this.mode = mode;
        this.scriptId = scriptId;
        this.x = x;
        this.z = z;
        this.cameraMode = cameraMode;
    }

    public C2SCameraCenterRequestPacket(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        this.scriptId = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.cameraMode = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        buf.writeUtf(scriptId);
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeBoolean(cameraMode);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（ChunkMap.move / ticket 变更必须主线程）
        try {
            CameraCenterManager.INSTANCE.handleRequest(player, mode, scriptId, x, z, cameraMode);
        } catch (RuntimeException e) {
            // 半更新状态（虚拟中心 / 锚点 / PlayerState）必须释放，异常继续交给 PacketGuard 记录
            CameraCenterManager.INSTANCE.releaseOnFailure(player);
            throw e;
        }
    }
}
