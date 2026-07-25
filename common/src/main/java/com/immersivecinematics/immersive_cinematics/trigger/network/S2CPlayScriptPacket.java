package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CPlayScriptPacket extends BaseS2CMessage {

    private final String scriptJson;

    public S2CPlayScriptPacket(String scriptJson) {
        this.scriptJson = scriptJson;
    }

    public S2CPlayScriptPacket(FriendlyByteBuf buf) {
        this.scriptJson = buf.readUtf();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.PLAY_SCRIPT;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptJson);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        // 在客户端主线程执行
        ClientScriptReceiver.handlePlayScript(this);
    }

    public String getScriptJson() { return scriptJson; }

    public static void send(ServerPlayer player, String scriptJson) {
        new S2CPlayScriptPacket(scriptJson).sendTo(player);
    }
}
