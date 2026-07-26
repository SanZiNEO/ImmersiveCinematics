package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class C2SPlaybackStartedPacket {

    private final String scriptId;

    public C2SPlaybackStartedPacket(String scriptId) {
        this.scriptId = scriptId;
    }

    public C2SPlaybackStartedPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
    }

    public String getScriptId() { return scriptId; }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        ctx.enqueueWork(() -> {
            TriggerEngine.INSTANCE.onPlaybackStarted(player, scriptId);
        });
        ctx.setPacketHandled(true);
    }
}
