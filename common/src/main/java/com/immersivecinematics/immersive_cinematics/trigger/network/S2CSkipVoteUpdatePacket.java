package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 → 客户端：某一播放实例的跳过投票进度（已投票人数 / 观看者总数）。
 *
 * <p>{@code youVoted} 是「收包者本人是否已投票」——每个观众各拿一份，故逐人发送而非广播同一份数值：
 * 客户端据此停止显示长按提示、不再响应长按。
 */
public class S2CSkipVoteUpdatePacket implements CinematicS2CPacket {

    private final String scriptId;
    private final String instanceId;
    private final int voterCount;
    private final int totalViewers;
    private final boolean youVoted;

    public S2CSkipVoteUpdatePacket(String scriptId, String instanceId, int voterCount, int totalViewers,
                                   boolean youVoted) {
        this.scriptId = scriptId;
        this.instanceId = instanceId != null ? instanceId : "";
        this.voterCount = voterCount;
        this.totalViewers = totalViewers;
        this.youVoted = youVoted;
    }

    public S2CSkipVoteUpdatePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.voterCount = buf.readVarInt();
        this.totalViewers = buf.readVarInt();
        this.youVoted = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId != null ? instanceId : "");
        buf.writeVarInt(voterCount);
        buf.writeVarInt(totalViewers);
        buf.writeBoolean(youVoted);
    }

    @Override
    public void handle() {
        ClientScriptReceiver.handleSkipVoteUpdate(this);
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public int getVoterCount() { return voterCount; }
    public int getTotalViewers() { return totalViewers; }
    public boolean isYouVoted() { return youVoted; }

    public static void send(ServerPlayer player, String scriptId, String instanceId, int voterCount,
                            int totalViewers, boolean youVoted) {
        NetworkHandler.sendToPlayer(player,
                new S2CSkipVoteUpdatePacket(scriptId, instanceId, voterCount, totalViewers, youVoted));
    }
}
