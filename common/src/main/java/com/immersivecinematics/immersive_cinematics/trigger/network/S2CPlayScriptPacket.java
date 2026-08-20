package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CPlayScriptPacket implements CinematicS2CPacket {

    private final String scriptJson;
    private final String refId;

    public S2CPlayScriptPacket(String scriptJson) {
        this(scriptJson, "");
    }

    public S2CPlayScriptPacket(String scriptJson, String refId) {
        this.scriptJson = scriptJson;
        this.refId = refId;
    }

    public S2CPlayScriptPacket(FriendlyByteBuf buf) {
        this.scriptJson = buf.readUtf();
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptJson);
        buf.writeUtf(refId);
    }

    @Override
    public void handle() {
        // 平台网络层保证在客户端主线程执行
        ClientScriptReceiver.handlePlayScript(this);
    }

    public String getScriptJson() { return scriptJson; }
    public String getRefId() { return refId; }

    public static void send(ServerPlayer player, String scriptJson) {
        NetworkHandler.sendToPlayer(player, new S2CPlayScriptPacket(scriptJson));
    }

    public static void send(ServerPlayer player, String scriptJson, String refId) {
        NetworkHandler.sendToPlayer(player, new S2CPlayScriptPacket(scriptJson, refId));
    }
}
