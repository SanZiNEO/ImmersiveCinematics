package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CPlayScriptPacket {

    private final String scriptJson;

    public S2CPlayScriptPacket(String scriptJson) {
        this.scriptJson = scriptJson;
    }

    public S2CPlayScriptPacket(FriendlyByteBuf buf) {
        this.scriptJson = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptJson);
    }

    public String getScriptJson() { return scriptJson; }

    public static void send(ServerPlayer player, String scriptJson) {
        NetworkHandler.sendToPlayer(new S2CPlayScriptPacket(scriptJson), player);
    }
}
