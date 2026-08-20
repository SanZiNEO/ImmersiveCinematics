package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class C2SScriptFinishedPacket implements CinematicC2SPacket {

    private final String scriptId;
    private final CompletionReason reason;
    private final String refId;

    public C2SScriptFinishedPacket(String scriptId, CompletionReason reason) {
        this(scriptId, reason, "");
    }

    public C2SScriptFinishedPacket(String scriptId, CompletionReason reason, String refId) {
        this.scriptId = scriptId;
        this.reason = reason;
        this.refId = refId;
    }

    public C2SScriptFinishedPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.reason = buf.readEnum(CompletionReason.class);
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeEnum(reason);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(ServerPlayer player) {
        // N1：stop 包的自然回执
        AckTracker.ack(refId);
        TriggerEngine.INSTANCE.onScriptFinished(player, scriptId, reason);
        // 区块预加载保底：任意退出（含强退）都强制释放，把加载交还玩家/原版机制
        com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager.INSTANCE.onScriptFinished(player);
    }

    public String getScriptId() { return scriptId; }
    public CompletionReason getReason() { return reason; }
    public String getRefId() { return refId; }
}
