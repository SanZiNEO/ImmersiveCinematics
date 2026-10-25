package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.trigger.network.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 网络桥接：C2S 走 {@code ServerPlayNetworking}，S2C 走 {@code ClientPlayNetworking}。
 */
public final class FabricNetwork implements NetworkBridge {

    public static void init() {
        NetworkHandler.setBridge(new FabricNetwork());

        // ===== C2S =====

        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_FINISHED), (server, player, handler, buf, responseSender) -> {
            C2SScriptFinishedPacket pkt = new C2SScriptFinishedPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.SCRIPT_FINISHED, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PLAYBACK_STARTED), (server, player, handler, buf, responseSender) -> {
            C2SPlaybackStartedPacket pkt = new C2SPlaybackStartedPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.PLAYBACK_STARTED, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_PAUSE), (server, player, handler, buf, responseSender) -> {
            C2SScriptPausePacket pkt = new C2SScriptPausePacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.SCRIPT_PAUSE, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_SAVED), (server, player, handler, buf, responseSender) -> {
            C2SScriptSavedPacket pkt = new C2SScriptSavedPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.SCRIPT_SAVED, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.CAMERA_CENTER_REQUEST), (server, player, handler, buf, responseSender) -> {
            C2SCameraCenterRequestPacket pkt = new C2SCameraCenterRequestPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.CAMERA_CENTER_REQUEST, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.CAMERA_CENTER_POSITION), (server, player, handler, buf, responseSender) -> {
            C2SCameraCenterPositionPacket pkt = new C2SCameraCenterPositionPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.CAMERA_CENTER_POSITION, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.AUDIO_LISTENER), (server, player, handler, buf, responseSender) -> {
            C2SAudioListenerPacket pkt = new C2SAudioListenerPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.AUDIO_LISTENER, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.RESOLVE_ENTITY_SELECTOR), (server, player, handler, buf, responseSender) -> {
            C2SResolveEntitySelectorPacket pkt = new C2SResolveEntitySelectorPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.RESOLVE_ENTITY_SELECTOR, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.RULE_COMMAND), (server, player, handler, buf, responseSender) -> {
            C2SRuleCommandPacket pkt = new C2SRuleCommandPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.RULE_COMMAND, player, () -> pkt.handle(player)));
        });
        ServerPlayNetworking.registerGlobalReceiver(id(NetworkHandler.CAMERA_VIEW), (server, player, handler, buf, responseSender) -> {
            C2SCameraViewPacket pkt = new C2SCameraViewPacket(buf);
            server.execute(() -> PacketGuard.handle("C2S", NetworkHandler.CAMERA_VIEW, player, () -> pkt.handle(player)));
        });

        // ===== S2C（仅客户端：专用服务端没有 ClientPlayNetworking）=====
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {

        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.PLAY_SCRIPT), (client, handler, buf, responseSender) -> {
            S2CPlayScriptPacket pkt = new S2CPlayScriptPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.PLAY_SCRIPT, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.STOP_SCRIPT), (client, handler, buf, responseSender) -> {
            S2CStopScriptPacket pkt = new S2CStopScriptPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.STOP_SCRIPT, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.TRIGGER_STATE_SYNC), (client, handler, buf, responseSender) -> {
            S2CTriggerStateSyncPacket pkt = new S2CTriggerStateSyncPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.TRIGGER_STATE_SYNC, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SKIP_VOTE_UPDATE), (client, handler, buf, responseSender) -> {
            S2CSkipVoteUpdatePacket pkt = new S2CSkipVoteUpdatePacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.SKIP_VOTE_UPDATE, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_PAUSE_ACK), (client, handler, buf, responseSender) -> {
            S2CScriptPauseAckPacket pkt = new S2CScriptPauseAckPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.SCRIPT_PAUSE_ACK, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.SCRIPT_RELOAD), (client, handler, buf, responseSender) -> {
            S2CScriptReloadPacket pkt = new S2CScriptReloadPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.SCRIPT_RELOAD, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.RESOLVE_ENTITY_SELECTOR_RESULT), (client, handler, buf, responseSender) -> {
            S2CResolveEntitySelectorResultPacket pkt = new S2CResolveEntitySelectorResultPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.RESOLVE_ENTITY_SELECTOR_RESULT, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.CAMERA_VIEW), (client, handler, buf, responseSender) -> {
            S2CCameraViewPacket pkt = new S2CCameraViewPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.CAMERA_VIEW, () -> pkt.handle()));
        });
        ClientPlayNetworking.registerGlobalReceiver(id(NetworkHandler.DIMENSION_INFO), (client, handler, buf, responseSender) -> {
            S2CDimensionInfoPacket pkt = new S2CDimensionInfoPacket(buf);
            client.execute(() -> PacketGuard.handle("S2C", NetworkHandler.DIMENSION_INFO, () -> pkt.handle()));
        });
        }
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
        if (packet instanceof C2SCameraCenterRequestPacket) return id(NetworkHandler.CAMERA_CENTER_REQUEST);
        if (packet instanceof C2SCameraCenterPositionPacket) return id(NetworkHandler.CAMERA_CENTER_POSITION);
        if (packet instanceof C2SAudioListenerPacket) return id(NetworkHandler.AUDIO_LISTENER);
        if (packet instanceof C2SRuleCommandPacket) return id(NetworkHandler.RULE_COMMAND);
        if (packet instanceof C2SResolveEntitySelectorPacket) return id(NetworkHandler.RESOLVE_ENTITY_SELECTOR);
        if (packet instanceof C2SCameraViewPacket) return id(NetworkHandler.CAMERA_VIEW);
        if (packet instanceof S2CPlayScriptPacket) return id(NetworkHandler.PLAY_SCRIPT);
        if (packet instanceof S2CStopScriptPacket) return id(NetworkHandler.STOP_SCRIPT);
        if (packet instanceof S2CTriggerStateSyncPacket) return id(NetworkHandler.TRIGGER_STATE_SYNC);
        if (packet instanceof S2CSkipVoteUpdatePacket) return id(NetworkHandler.SKIP_VOTE_UPDATE);
        if (packet instanceof S2CScriptPauseAckPacket) return id(NetworkHandler.SCRIPT_PAUSE_ACK);
        if (packet instanceof S2CScriptReloadPacket) return id(NetworkHandler.SCRIPT_RELOAD);
        if (packet instanceof S2CResolveEntitySelectorResultPacket) return id(NetworkHandler.RESOLVE_ENTITY_SELECTOR_RESULT);
        if (packet instanceof S2CCameraViewPacket) return id(NetworkHandler.CAMERA_VIEW);
        if (packet instanceof S2CDimensionInfoPacket) return id(NetworkHandler.DIMENSION_INFO);
        throw new IllegalArgumentException("Unknown cinematic packet: " + packet.getClass().getName());
    }
}
