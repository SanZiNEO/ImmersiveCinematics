package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：观众对当前播放实例投出一张跳过票。
 *
 * <p>投票只计票，不结束投票者自己的播放：票数达到比例后由服务端统一向该批全部在场者广播停止。
 * 重复投票在服务端由 {@code FactLedger} 幂等吞掉，网络层不做去重。
 */
public class C2SSkipVotePacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id；空串 = 无实例 id 的来源（退化账本键） */
    private final String instanceId;

    public C2SSkipVotePacket(String scriptId, String instanceId) {
        this.scriptId = scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
    }

    public C2SSkipVotePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.onSkipVote(player, scriptId, instanceId);
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
}
