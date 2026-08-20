package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.trigger.network.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 网络桥接（0.3.5 第7轮去 Arch）。
 */
public final class ForgeNetwork implements NetworkBridge {

    private static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel CHANNEL;

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(ImmersiveCinematics.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals);

        NetworkHandler.setBridge(new ForgeNetwork());

        int id = 0;
        // ===== C2S =====
        CHANNEL.registerMessage(id++, C2SScriptFinishedPacket.class,
                CinematicPacket::write, C2SScriptFinishedPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(() -> pkt.handle(ctx.get().getSender())); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, C2SPlaybackStartedPacket.class,
                CinematicPacket::write, C2SPlaybackStartedPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(() -> pkt.handle(ctx.get().getSender())); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, C2SScriptPausePacket.class,
                CinematicPacket::write, C2SScriptPausePacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(() -> pkt.handle(ctx.get().getSender())); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, C2SScriptSavedPacket.class,
                CinematicPacket::write, C2SScriptSavedPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(() -> pkt.handle(ctx.get().getSender())); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, C2SPreloadRequestPacket.class,
                CinematicPacket::write, C2SPreloadRequestPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(() -> pkt.handle(ctx.get().getSender())); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, C2SPreloadPositionPacket.class,
                CinematicPacket::write, C2SPreloadPositionPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(() -> pkt.handle(ctx.get().getSender())); ctx.get().setPacketHandled(true); });

        // ===== S2C =====
        CHANNEL.registerMessage(id++, S2CPlayScriptPacket.class,
                CinematicPacket::write, S2CPlayScriptPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, S2CStopScriptPacket.class,
                CinematicPacket::write, S2CStopScriptPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, S2CTriggerStateSyncPacket.class,
                CinematicPacket::write, S2CTriggerStateSyncPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, S2CSkipVoteUpdatePacket.class,
                CinematicPacket::write, S2CSkipVoteUpdatePacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, S2CScriptPauseAckPacket.class,
                CinematicPacket::write, S2CScriptPauseAckPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, S2CScriptReloadPacket.class,
                CinematicPacket::write, S2CScriptReloadPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
        CHANNEL.registerMessage(id++, S2CPreloadResultPacket.class,
                CinematicPacket::write, S2CPreloadResultPacket::new,
                (pkt, ctx) -> { ctx.get().enqueueWork(pkt::handle); ctx.get().setPacketHandled(true); });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CinematicS2CPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    @Override
    public void sendToServer(CinematicC2SPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
