package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CStopScriptPacket extends BaseS2CMessage {

    private final String scriptId;
    private final String refId;

    public S2CStopScriptPacket(String scriptId) {
        this(scriptId, "");
    }

    public S2CStopScriptPacket(String scriptId, String refId) {
        this.scriptId = scriptId;
        this.refId = refId;
    }

    public S2CStopScriptPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.refId = buf.readUtf();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.STOP_SCRIPT;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ClientScriptReceiver.handleStopScript(this);
    }

    public String getScriptId() { return scriptId; }
    public String getRefId() { return refId; }

    public static void send(ServerPlayer player, String scriptId) {
        new S2CStopScriptPacket(scriptId).sendTo(player);
    }

    public static void send(ServerPlayer player, String scriptId, String refId) {
        new S2CStopScriptPacket(scriptId, refId).sendTo(player);
    }
}
