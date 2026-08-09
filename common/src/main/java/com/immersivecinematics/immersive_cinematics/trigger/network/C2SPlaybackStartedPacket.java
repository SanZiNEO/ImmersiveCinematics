package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class C2SPlaybackStartedPacket extends BaseC2SMessage {

    private final String scriptId;
    private final String refId;

    public C2SPlaybackStartedPacket(String scriptId) {
        this(scriptId, "");
    }

    public C2SPlaybackStartedPacket(String scriptId, String refId) {
        this.scriptId = scriptId;
        this.refId = refId;
    }

    public C2SPlaybackStartedPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.refId = buf.readUtf();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.PLAYBACK_STARTED;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        // N1：play 包的自然回执
        AckTracker.ack(refId);
        TriggerEngine.INSTANCE.onPlaybackStarted(player, scriptId);
    }

    public String getScriptId() { return scriptId; }
    public String getRefId() { return refId; }
}
