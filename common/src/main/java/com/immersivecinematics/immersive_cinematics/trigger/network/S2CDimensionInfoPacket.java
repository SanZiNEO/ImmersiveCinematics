package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * 维度元数据（S2C）：维度 id → 维度类型 id。
 *
 * 客户端创建目标维度 {@code ClientLevel} 时需要维度类型（天空 / 光照 / 坐标缩放）；
 * 原版只下发玩家当前维度的类型，跨维度视图必须由服务端补一份。玩家加入时整表下发一次，
 * 客户端写入 {@link DimensionViews#registerDimensionType}（并发 map，网络线程安全）。
 */
public class S2CDimensionInfoPacket implements CinematicS2CPacket {

    private final Map<ResourceLocation, ResourceLocation> dimensionToType;

    public S2CDimensionInfoPacket(Map<ResourceLocation, ResourceLocation> dimensionToType) {
        this.dimensionToType = dimensionToType;
    }

    public S2CDimensionInfoPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<ResourceLocation, ResourceLocation> map = new HashMap<>(Math.max(4, count));
        for (int i = 0; i < count; i++) {
            ResourceLocation dimension = buf.readResourceLocation();
            ResourceLocation type = buf.readResourceLocation();
            map.put(dimension, type);
        }
        this.dimensionToType = map;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(dimensionToType.size());
        for (Map.Entry<ResourceLocation, ResourceLocation> entry : dimensionToType.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeResourceLocation(entry.getValue());
        }
    }

    @Override
    public void handle() {
        for (Map.Entry<ResourceLocation, ResourceLocation> entry : dimensionToType.entrySet()) {
            DimensionViews.registerDimensionType(entry.getKey(), entry.getValue());
        }
    }

    /** 汇总服务端全部维度的 id → 类型，发给单个玩家（玩家加入时调用一次）。 */
    public static void send(ServerPlayer player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }
        Map<ResourceLocation, ResourceLocation> map = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dimension = level.dimension().location();
            ResourceLocation type = level.dimensionTypeRegistration().unwrapKey()
                    .map(ResourceKey::location)
                    .orElse(dimension);
            map.put(dimension, type);
        }
        NetworkHandler.sendToPlayer(player, new S2CDimensionInfoPacket(map));
    }

    public Map<ResourceLocation, ResourceLocation> dimensionToType() {
        return dimensionToType;
    }
}
