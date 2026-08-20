package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CSkipVoteUpdatePacket implements CinematicS2CPacket {

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
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeVarInt(voterCount);
        buf.writeVarInt(totalViewers);
    }

    @Override
    public void handle() {
        ClientScriptReceiver.handleSkipVoteUpdate(this);
    }

    public String getScriptId() { return scriptId; }
    public int getVoterCount() { return voterCount; }
    public int getTotalViewers() { return totalViewers; }

    public static void send(ServerPlayer player, String scriptId, int voterCount, int totalViewers) {
        NetworkHandler.sendToPlayer(player, new S2CSkipVoteUpdatePacket(scriptId, voterCount, totalViewers));
    }
}
