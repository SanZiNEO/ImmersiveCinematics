package com.immersivecinematics.immersive_cinematics.trigger.server;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 相机实体同步器：把相机半径内的实体包主动发给真实玩家。
 * <p>
 * 不用假人：为每个实体创建一个专用 {@link ServerEntity}，broadcast 指向真实玩家的 connection，
 * 初始 sendPairingData + 每 tick sendChanges，原版自己算移动/旋转/元数据/装备等增量包。
 */
public final class CameraEntitySyncManager {

    public static final CameraEntitySyncManager INSTANCE = new CameraEntitySyncManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraEntitySync");

    private final Map<UUID, AnchorSync> anchors = new HashMap<>();

    private CameraEntitySyncManager() {}

    public void setAnchor(UUID player, ServerLevel level, ChunkPos center, int radius) {
        AnchorSync a = anchors.get(player);
        if (a != null && a.level != level) {
            removeAnchor(player);
            a = null;
        }
        if (a == null) {
            a = new AnchorSync(player, level);
            anchors.put(player, a);
        }
        a.center = center;
        a.radius = Math.max(1, Math.min(16, radius));
        // 玩家可能重连/更新引用
        ServerPlayer real = level.getServer().getPlayerList().getPlayer(player);
        if (real != null) {
            a.real = real;
        }
    }

    public void removeAnchor(UUID player) {
        AnchorSync a = anchors.remove(player);
        if (a == null) return;
        sendRemovesFor(a, null);
        a.trackers.clear();
        LOGGER.info("[camera-entity] 同步器移除 玩家={}", player);
    }

    public void tick() {
        for (AnchorSync a : anchors.values()) {
            tickAnchor(a);
        }
    }

    private void tickAnchor(AnchorSync a) {
        if (a.real == null || a.real.connection == null || a.center == null) {
            return;
        }
        Map<Integer, Entity> current = new HashMap<>();
        int r = a.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(a.center.x + dx, a.center.z + dz);
                if (!a.level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                AABB box = new AABB(
                        pos.getMinBlockX(), a.level.getMinBuildHeight(), pos.getMinBlockZ(),
                        pos.getMaxBlockX() + 1.0, a.level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1.0);
                for (Entity e : a.level.getEntities((Entity) null, box, e -> !isExcluded(a, e))) {
                    if (isNearRealPlayer(a, e)) continue; // 原版自己会跟踪
                    current.put(e.getId(), e);
                }
            }
        }

        // 新增/更新
        for (Map.Entry<Integer, Entity> entry : current.entrySet()) {
            int id = entry.getKey();
            Entity e = entry.getValue();
            ServerEntity tracker = a.trackers.get(id);
            if (tracker == null) {
                tracker = new ServerEntity(a.level, e, 1, false, a.real.connection::send);
                tracker.sendPairingData(a.real, a.real.connection::send);
                a.trackers.put(id, tracker);
            } else {
                tracker.sendChanges();
            }
        }

        // 移除离开/消失/进入原版范围的
        IntList removes = new IntArrayList();
        for (Map.Entry<Integer, ServerEntity> entry : a.trackers.entrySet()) {
            int id = entry.getKey();
            Entity e = a.level.getEntity(id);
            boolean gone = e == null || !current.containsKey(id);
            if (gone) {
                // 只有确实不在原版跟踪范围内的才需要补 Remove；进入原版范围时原版会接管
                if (e == null || !isNearRealPlayer(a, e)) {
                    removes.add(id);
                }
                entry.getValue(); // no-op keep reference
            }
        }
        if (!removes.isEmpty()) {
            a.real.connection.send(new ClientboundRemoveEntitiesPacket(removes));
        }
        removes.forEach(a.trackers::remove);
    }

    private void sendRemovesFor(AnchorSync a, IntList extra) {
        if (a.real == null || a.real.connection == null) return;
        IntList removes = new IntArrayList();
        for (int id : a.trackers.keySet()) {
            Entity e = a.level.getEntity(id);
            if (e == null || !isNearRealPlayer(a, e)) {
                removes.add(id);
            }
        }
        if (extra != null) removes.addAll(extra);
        if (!removes.isEmpty()) {
            a.real.connection.send(new ClientboundRemoveEntitiesPacket(removes));
        }
    }

    private boolean isExcluded(AnchorSync a, Entity e) {
        return e == a.real;
    }

    private boolean isNearRealPlayer(AnchorSync a, Entity e) {
        double radius = e.getType().clientTrackingRange() * 16.0;
        double dx = e.getX() - a.real.getX();
        double dz = e.getZ() - a.real.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private static final class AnchorSync {
        final UUID player;
        final ServerLevel level;
        ServerPlayer real;
        ChunkPos center;
        int radius = 2;
        final Map<Integer, ServerEntity> trackers = new HashMap<>();

        AnchorSync(UUID player, ServerLevel level) {
            this.player = player;
            this.level = level;
        }
    }
}
