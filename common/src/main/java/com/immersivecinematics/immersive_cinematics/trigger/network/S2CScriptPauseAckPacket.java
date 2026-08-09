package com.immersivecinematics.immersive_cinematics.trigger.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 → 客户端：暂停/恢复包的 ACK 回执（N1）。
 * <p>
 * 客户端收到后 {@link AckTracker#ack} 对应 refId，确认包不丢。
 */
public class S2CScriptPauseAckPacket extends BaseS2CMessage {

    private final String refId;

    public S2CScriptPauseAckPacket(String refId) {
        this.refId = refId;
    }

    public S2CScriptPauseAckPacket(FriendlyByteBuf buf) {
        this.refId = buf.readUtf();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.SCRIPT_PAUSE_ACK;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(refId);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        AckTracker.ack(refId);
    }

    public String getRefId() { return refId; }

    public static void send(ServerPlayer player, String refId) {
        new S2CScriptPauseAckPacket(refId).sendTo(player);
    }
}
