package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CStopScriptPacket implements CinematicS2CPacket {

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
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(refId);
    }

    @Override
    public void handle() {
        ClientScriptReceiver.handleStopScript(this);
    }

    public String getScriptId() { return scriptId; }
    public String getRefId() { return refId; }

    public static void send(ServerPlayer player, String scriptId) {
        NetworkHandler.sendToPlayer(player, new S2CStopScriptPacket(scriptId));
    }

    public static void send(ServerPlayer player, String scriptId, String refId) {
        NetworkHandler.sendToPlayer(player, new S2CStopScriptPacket(scriptId, refId));
    }
}
