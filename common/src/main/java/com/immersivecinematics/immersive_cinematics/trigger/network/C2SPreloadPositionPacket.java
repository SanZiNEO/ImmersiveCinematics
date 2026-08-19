package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** 相机位置上报（C2S，默认 20 tick 一次）：滑动窗口中心的相机区块坐标（方块坐标） */
public class C2SPreloadPositionPacket extends BaseC2SMessage {

    private final int x;
    private final int z;

    public C2SPreloadPositionPacket(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public C2SPreloadPositionPacket(FriendlyByteBuf buf) {
        this.x = buf.readInt();
        this.z = buf.readInt();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.PRELOAD_POS;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        // 回服务端主线程（同 C2SPreloadRequestPacket）：ticket 变更/发包必须主线程
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        context.queue(() -> ChunkPreloadManager.INSTANCE.handlePosition(player, x, z));
    }
}
