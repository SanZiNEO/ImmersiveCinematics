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

    public C2SScriptFinishedPacket(String scriptId, CompletionReason reason) {
        this.scriptId = scriptId;
        this.reason = reason;
    }

    public C2SScriptFinishedPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.reason = buf.readEnum(CompletionReason.class);
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.SCRIPT_FINISHED;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeEnum(reason);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        TriggerEngine.INSTANCE.onScriptFinished(player, scriptId, reason);
    }

    public String getScriptId() { return scriptId; }
    public CompletionReason getReason() { return reason; }
}
