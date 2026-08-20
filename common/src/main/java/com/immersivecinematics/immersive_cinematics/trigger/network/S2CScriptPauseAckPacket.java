package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 → 客户端：暂停/恢复包的 ACK 回执（N1）。
 * <p>
 * 客户端收到后 {@link AckTracker#ack} 对应 refId，确认包不丢。
 */
public class S2CScriptPauseAckPacket implements CinematicS2CPacket {

    private final String refId;

    public S2CScriptPauseAckPacket(String refId) {
        this.refId = refId;
    }

    public S2CScriptPauseAckPacket(FriendlyByteBuf buf) {
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
        NetworkHandler.sendToPlayer(player, new S2CScriptPauseAckPacket(refId));
    }
}
