package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class S2CSkipVoteUpdatePacket {

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

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeVarInt(voterCount);
        buf.writeVarInt(totalViewers);
    }

    public String getScriptId() { return scriptId; }
    public int getVoterCount() { return voterCount; }
    public int getTotalViewers() { return totalViewers; }

    public static void send(ServerPlayer player, String scriptId, int voterCount, int totalViewers) {
        NetworkHandler.sendToPlayer(new S2CSkipVoteUpdatePacket(scriptId, voterCount, totalViewers), player);
    }
}
