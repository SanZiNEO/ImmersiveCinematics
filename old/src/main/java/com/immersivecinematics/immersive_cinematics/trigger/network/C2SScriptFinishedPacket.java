package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class C2SScriptFinishedPacket {

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

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeEnum(reason);
    }

    public String getScriptId() { return scriptId; }
    public CompletionReason getReason() { return reason; }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine.INSTANCE
                .onScriptFinished(player, scriptId, reason);
    }
}
