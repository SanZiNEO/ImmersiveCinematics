package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientEntitySelectorCache;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：实体选择器解析结果（UUID 列表）。
 */
public class S2CResolveEntitySelectorResultPacket implements CinematicS2CPacket {

    private static final int MAX_UUIDS = 64;

    private final int requestId;
    private final boolean success;
    private final String error;
    private final List<UUID> uuids;

    public S2CResolveEntitySelectorResultPacket(int requestId, boolean success, String error, List<UUID> uuids) {
        this.requestId = requestId;
        this.success = success;
        this.error = error != null ? error : "";
        this.uuids = uuids != null ? uuids : List.of();
    }

    public S2CResolveEntitySelectorResultPacket(FriendlyByteBuf buf) {
        this.requestId = buf.readInt();
        this.success = buf.readBoolean();
        this.error = buf.readUtf(512);
        int size = buf.readInt();
        if (size < 0 || size > MAX_UUIDS) {
            throw new IllegalArgumentException("invalid uuid count: " + size);
        }
        List<UUID> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readUUID());
        }
        this.uuids = list;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeBoolean(success);
        buf.writeUtf(error, 512);
        buf.writeInt(uuids.size());
        for (UUID uuid : uuids) {
            buf.writeUUID(uuid);
        }
    }

    @Override
    public void handle() {
        ClientEntitySelectorCache.onResult(requestId, success, error, uuids);
    }
}
