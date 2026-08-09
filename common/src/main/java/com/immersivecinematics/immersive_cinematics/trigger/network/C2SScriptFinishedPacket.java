package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class C2SScriptFinishedPacket extends BaseC2SMessage {

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
    public MessageType getType() {
        return NetworkHandler.SCRIPT_FINISHED;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeEnum(reason);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        // N1：stop 包的自然回执
        AckTracker.ack(refId);
        TriggerEngine.INSTANCE.onScriptFinished(player, scriptId, reason);
    }

    public String getScriptId() { return scriptId; }
    public CompletionReason getReason() { return reason; }
    public String getRefId() { return refId; }
}
