package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CStopScriptPacket {

    private final String scriptId;

    public S2CStopScriptPacket(String scriptId) {
        this.scriptId = scriptId;
    }

    public S2CStopScriptPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
    }

    public String getScriptId() { return scriptId; }

    public static void send(ServerPlayer player, String scriptId) {
        NetworkHandler.sendToPlayer(new S2CStopScriptPacket(scriptId), player);
    }
}
