package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.CameraViewPacketRouter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 跨维度视图数据包（S2C）：把一条原版 PLAY 客户端包（区块 / 实体）打上目标维度标签后转发。
 *
 * 字段：视图 id + 目标维度 + 原版包 id + 原版包字节。客户端按维度取 {@code DimensionView}，
 * 临时把全局 {@code Minecraft.level} / {@code levelRenderer} / {@code ClientPacketListener.level}
 * 指向目标视图，再让原版包在目标世界上处理，最后还原。
 *
 * 只有服务端主动流式的包走这里；玩家自己维度的包仍走原版连接，不经过本通道。
 */
public class S2CCameraViewPacket implements CinematicS2CPacket {

    private final String viewId;
    private final ResourceLocation dimension;
    private final int vanillaPacketId;
    private final byte[] payload;

    public S2CCameraViewPacket(String viewId, ResourceLocation dimension, int vanillaPacketId, byte[] payload) {
        this.viewId = viewId;
        this.dimension = dimension;
        this.vanillaPacketId = vanillaPacketId;
        this.payload = payload;
    }

    public S2CCameraViewPacket(FriendlyByteBuf buf) {
        this.viewId = buf.readUtf();
        this.dimension = buf.readResourceLocation();
        this.vanillaPacketId = buf.readVarInt();
        this.payload = buf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(viewId);
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(vanillaPacketId);
        buf.writeByteArray(payload);
    }

    @Override
    public void handle() {
        CameraViewPacketRouter.handle(this);
    }

    public String viewId() {
        return viewId;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public int vanillaPacketId() {
        return vanillaPacketId;
    }

    public byte[] payload() {
        return payload;
    }
}
