package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：脚本播放结束回执（含完成原因枚举）。
 * <p>
 * {@code instanceId} 为 {@link S2CPlayScriptPacket#getInstanceId()} 的原样回传（§3.7），
 * 服务端据此在账本中按 {@code (scriptId, instanceId)} 精确解析本条结束信号所属的播放实例；
 * 无实例 id 的来源（本地播放、旧路径）回传空串，对应 {@code (scriptId, "")} 的退化账本键。
 */
public class C2SScriptFinishedPacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id（§3.7）；空串=无实例 id 的来源 */
    private final String instanceId;
    private final CompletionReason reason;
    private final String refId;

    public C2SScriptFinishedPacket(String scriptId, String instanceId, CompletionReason reason, String refId) {
        this.scriptId = scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.reason = reason;
        this.refId = refId;
    }

    public C2SScriptFinishedPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.reason = buf.readEnum(CompletionReason.class);
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeEnum(reason);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(ServerPlayer player) {
        // N1：stop 包的自然回执
        AckTracker.ack(refId);
        TriggerEngine.INSTANCE.onScriptFinished(player, scriptId, instanceId, reason);
        // 区块预加载保底：任意退出（含强退）都强制释放，把加载交还玩家/原版机制
        com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager.INSTANCE.onScriptFinished(player);
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public CompletionReason getReason() { return reason; }
    public String getRefId() { return refId; }
}
