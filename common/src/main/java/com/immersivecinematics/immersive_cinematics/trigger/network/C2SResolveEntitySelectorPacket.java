package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.EntitySelectorResolver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 客户端 → 服务端：请求用原版实体选择器（含 nbt=）解析实体。
 */
public class C2SResolveEntitySelectorPacket implements CinematicC2SPacket {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/EntitySelector");

    private final int requestId;
    private final String selector;
    private final double x;
    private final double y;
    private final double z;

    public C2SResolveEntitySelectorPacket(int requestId, String selector, double x, double y, double z) {
        this.requestId = requestId;
        this.selector = selector != null ? selector : "";
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public C2SResolveEntitySelectorPacket(FriendlyByteBuf buf) {
        this.requestId = buf.readInt();
        this.selector = buf.readUtf(512);
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeUtf(selector, 512);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    @Override
    public void handle(ServerPlayer player) {
        try {
            List<UUID> uuids = EntitySelectorResolver.resolve(player, selector, x, y, z);
            NetworkHandler.sendToPlayer(player, new S2CResolveEntitySelectorResultPacket(requestId, true, "", uuids));
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) {
                message = e.getClass().getSimpleName();
            }
            LOGGER.warn("实体选择器解析失败 player={} selector='{}': {}", player.getName().getString(), selector, message);
            NetworkHandler.sendToPlayer(player, new S2CResolveEntitySelectorResultPacket(requestId, false, message, List.of()));
        }
    }
}
