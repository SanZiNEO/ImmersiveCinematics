package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** 区块预加载请求（C2S）：模式 PRELOAD(0) / PREWARM(1) / RELEASE(2)，携带相机中心坐标（方块）+ 窗口半径 */
public class C2SPreloadRequestPacket extends BaseC2SMessage {

    public static final int MODE_PRELOAD = 0;
    public static final int MODE_PREWARM = 1;
    public static final int MODE_RELEASE = 2;

    private final int mode;
    private final String scriptId;
    private final int x;
    private final int z;
    private final int radius;

    public C2SPreloadRequestPacket(int mode, String scriptId, int x, int z, int radius) {
        this.mode = mode;
        this.scriptId = scriptId;
        this.x = x;
        this.z = z;
        this.radius = radius;
    }

    public C2SPreloadRequestPacket(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        this.scriptId = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.radius = buf.readInt();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.PRELOAD_REQ;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        buf.writeUtf(scriptId);
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeInt(radius);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        // 必须回服务端主线程：addRegionTicket/removeRegionTicket/connection.send 都要主线程，
        // 否则网络线程改 DistanceManager 会把区块 ticket 状态写坏（症状：个别区块永久不加载）
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        context.queue(() -> ChunkPreloadManager.INSTANCE.handleRequest(player, mode, scriptId, x, z, radius));
    }
}
