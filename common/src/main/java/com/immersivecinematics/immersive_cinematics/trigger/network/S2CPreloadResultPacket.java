package com.immersivecinematics.immersive_cinematics.trigger.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 区块预加载回执（S2C）：仅"无需加载/命中"信息，日志用，不阻塞播放 */
public class S2CPreloadResultPacket extends BaseS2CMessage {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Preload");

    private final String scriptId;
    private final String message;

    public S2CPreloadResultPacket(String scriptId, String message) {
        this.scriptId = scriptId;
        this.message = message;
    }

    public S2CPreloadResultPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.message = buf.readUtf();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.PRELOAD_RESULT;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(message);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        LOGGER.info("[preload] {}: {}", scriptId, message);
    }

    public static void send(ServerPlayer player, String scriptId, String message) {
        new S2CPreloadResultPacket(scriptId, message).sendTo(player);
    }
}
