package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.trigger.network.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 网络桥接（0.3.5 第7轮去 Arch）。
 */
public final class FabricNetwork implements NetworkBridge {

    public static void init() {
        NetworkHandler.setBridge(new FabricNetwork());

        // ===== C2S =====

        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_FINISHED), (server, player, handler, buf, responseSender) -> {
            C2SScriptFinishedPacket pkt = new C2SScriptFinishedPacket(buf);
            server.execute(() -> pkt.handle(player));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PLAYBACK_STARTED), (server, player, handler, buf, responseSender) -> {
            C2SPlaybackStartedPacket pkt = new C2SPlaybackStartedPacket(buf);
            server.execute(() -> pkt.handle(player));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_PAUSE), (server, player, handler, buf, responseSender) -> {
            C2SScriptPausePacket pkt = new C2SScriptPausePacket(buf);
            server.execute(() -> pkt.handle(player));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_SAVED), (server, player, handler, buf, responseSender) -> {
            C2SScriptSavedPacket pkt = new C2SScriptSavedPacket(buf);
            server.execute(() -> pkt.handle(player));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PRELOAD_REQ), (server, player, handler, buf, responseSender) -> {
            C2SPreloadRequestPacket pkt = new C2SPreloadRequestPacket(buf);
            server.execute(() -> pkt.handle(player));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PRELOAD_POS), (server, player, handler, buf, responseSender) -> {
            C2SPreloadPositionPacket pkt = new C2SPreloadPositionPacket(buf);
            server.execute(() -> pkt.handle(player));
        });

        // ===== S2C =====

        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PLAY_SCRIPT), (client, handler, buf, responseSender) -> {
            S2CPlayScriptPacket pkt = new S2CPlayScriptPacket(buf);
            client.execute(pkt::handle);
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.STOP_SCRIPT), (client, handler, buf, responseSender) -> {
            S2CStopScriptPacket pkt = new S2CStopScriptPacket(buf);
            client.execute(pkt::handle);
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.TRIGGER_STATE_SYNC), (client, handler, buf, responseSender) -> {
            S2CTriggerStateSyncPacket pkt = new S2CTriggerStateSyncPacket(buf);
            client.execute(pkt::handle);
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SKIP_VOTE_UPDATE), (client, handler, buf, responseSender) -> {
            S2CSkipVoteUpdatePacket pkt = new S2CSkipVoteUpdatePacket(buf);
            client.execute(pkt::handle);
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_PAUSE_ACK), (client, handler, buf, responseSender) -> {
            S2CScriptPauseAckPacket pkt = new S2CScriptPauseAckPacket(buf);
            client.execute(pkt::handle);
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_RELOAD), (client, handler, buf, responseSender) -> {
            S2CScriptReloadPacket pkt = new S2CScriptReloadPacket(buf);
            client.execute(pkt::handle);
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PRELOAD_RESULT), (client, handler, buf, responseSender) -> {
            S2CPreloadResultPacket pkt = new S2CPreloadResultPacket(buf);
            client.execute(pkt::handle);
        });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CinematicS2CPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        packet.write(buf);
        // Fabric S2C 需要知道包 ID；这里通过简单映射：packet 类 → ID
        ServerPlayNetworking.send(player, idFor(packet), buf);
    }

    @Override
    public void sendToServer(CinematicC2SPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        packet.write(buf);
        ClientPlayNetworking.send(idFor(packet), buf);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(ImmersiveCinematics.MOD_ID, path);
    }

    private static ResourceLocation idFor(CinematicPacket packet) {
        if (packet instanceof C2SScriptFinishedPacket) return id(NetworkHandler.SCRIPT_FINISHED);
        if (packet instanceof C2SPlaybackStartedPacket) return id(NetworkHandler.PLAYBACK_STARTED);
        if (packet instanceof C2SScriptPausePacket) return id(NetworkHandler.SCRIPT_PAUSE);
        if (packet instanceof C2SScriptSavedPacket) return id(NetworkHandler.SCRIPT_SAVED);
        if (packet instanceof C2SPreloadRequestPacket) return id(NetworkHandler.PRELOAD_REQ);
        if (packet instanceof C2SPreloadPositionPacket) return id(NetworkHandler.PRELOAD_POS);
        if (packet instanceof S2CPlayScriptPacket) return id(NetworkHandler.PLAY_SCRIPT);
        if (packet instanceof S2CStopScriptPacket) return id(NetworkHandler.STOP_SCRIPT);
        if (packet instanceof S2CTriggerStateSyncPacket) return id(NetworkHandler.TRIGGER_STATE_SYNC);
        if (packet instanceof S2CSkipVoteUpdatePacket) return id(NetworkHandler.SKIP_VOTE_UPDATE);
        if (packet instanceof S2CScriptPauseAckPacket) return id(NetworkHandler.SCRIPT_PAUSE_ACK);
        if (packet instanceof S2CScriptReloadPacket) return id(NetworkHandler.SCRIPT_RELOAD);
        if (packet instanceof S2CPreloadResultPacket) return id(NetworkHandler.PRELOAD_RESULT);
        throw new IllegalArgumentException("Unknown cinematic packet: " + packet.getClass().getName());
    }
}
