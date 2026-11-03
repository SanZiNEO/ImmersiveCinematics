package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 → 客户端：seek 包的 ACK 回执。
 *
 * 客户端收到后 {@link AckTracker#ack} 对应 refId，确认包不丢。
 */
public class S2CScriptSeekAckPacket implements CinematicS2CPacket {

    private final String refId;

    public S2CScriptSeekAckPacket(String refId) {
        this.refId = refId;
    }

    public S2CScriptSeekAckPacket(FriendlyByteBuf buf) {
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(refId);
    }

    @Override
    public void handle() {
        AckTracker.ack(refId);
    }

    public String getRefId() { return refId; }

    public static void send(ServerPlayer player, String refId) {
        NetworkHandler.sendScriptSeekAck(player, refId);
    }
}
