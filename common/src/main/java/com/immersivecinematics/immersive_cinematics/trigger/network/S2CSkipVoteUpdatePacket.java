package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CSkipVoteUpdatePacket extends BaseS2CMessage {

    private final String scriptId;
    private final int voterCount;
    private final int totalViewers;

    public S2CSkipVoteUpdatePacket(String scriptId, int voterCount, int totalViewers) {
        this.scriptId = scriptId;
        this.voterCount = voterCount;
        this.totalViewers = totalViewers;
    }

    public S2CSkipVoteUpdatePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.voterCount = buf.readVarInt();
        this.totalViewers = buf.readVarInt();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.SKIP_VOTE_UPDATE;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeVarInt(voterCount);
        buf.writeVarInt(totalViewers);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ClientScriptReceiver.handleSkipVoteUpdate(this);
    }

    public String getScriptId() { return scriptId; }
    public int getVoterCount() { return voterCount; }
    public int getTotalViewers() { return totalViewers; }

    public static void send(ServerPlayer player, String scriptId, int voterCount, int totalViewers) {
        new S2CSkipVoteUpdatePacket(scriptId, voterCount, totalViewers).sendTo(player);
    }
}
