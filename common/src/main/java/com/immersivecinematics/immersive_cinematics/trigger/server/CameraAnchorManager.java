package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 相机锚点管理（纯坐标版，不创建任何实体）。
 * <p>
 * 只记录“哪个玩家、哪个维度、相机中心在哪、刷怪半径多少”，
 * 供区块加载/刷怪/实体同步直接查询，不借助任何 Player 实体。
 */
public final class CameraAnchorManager {

    public static final CameraAnchorManager INSTANCE = new CameraAnchorManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraAnchor");

    private final Map<UUID, Anchor> anchors = new HashMap<>();

    private CameraAnchorManager() {}

    public void setAnchor(UUID player, ServerLevel level, ChunkPos center, int radius, boolean spawn, boolean ai) {
        Anchor a = anchors.get(player);
        if (a != null && a.level != level) {
            removeAnchor(player);
            a = null;
        }
        if (a == null) {
            a = new Anchor(player, level);
            anchors.put(player, a);
        }
        a.center = center;
        a.radius = Math.max(1, Math.min(16, radius));
        a.spawn = spawn;
        a.ai = ai;
        a.virtual = new CameraAnchorVirtualPlayer(level, centerBlockX(center), groundY(level, center), centerBlockZ(center));
        LOGGER.info("[camera-anchor] 锚点更新 玩家={} 中心={}", player, center);
    }

    public void removeAnchor(UUID player) {
        Anchor a = anchors.remove(player);
        if (a != null) {
            LOGGER.info("[camera-anchor] 锚点移除 玩家={}", player);
        }
    }

    public boolean hasAnchor(UUID player) {
        return anchors.containsKey(player);
    }

    /** 服务端 tick：纯坐标锚点无需移动实体，保留占位 */
    public void tick() {
    }

    /** 刷怪 mixin 查询：某区块附近是否存在相机锚点（距离 &lt; 128 格，同原版刷怪判定） */
    public boolean isAnyAnchorNear(ServerLevel level, ChunkPos chunkPos) {
        for (Anchor a : anchors.values()) {
            if (a.level != level || a.center == null) continue;
            double dx = (chunkPos.x << 4) + 8 - centerBlockX(a.center);
            double dz = (chunkPos.z << 4) + 8 - centerBlockZ(a.center);
            if (dx * dx + dz * dz < 16384.0) {
                return true;
            }
        }
        return false;
    }

    /** 给 NaturalSpawner 返回一个“虚拟玩家”引用；没有锚点则返回 null */
    public Player getVirtualPlayer(ServerLevel level, BlockPos pos) {
        for (Anchor a : anchors.values()) {
            if (a.level != level || a.virtual == null || a.center == null) continue;
            double dx = pos.getX() - a.virtual.getX();
            double dz = pos.getZ() - a.virtual.getZ();
            if (dx * dx + dz * dz < 16384.0) {
                return a.virtual;
            }
        }
        return null;
    }

    private static int centerBlockX(ChunkPos c) {
        return (c.x << 4) + 8;
    }

    private static int centerBlockZ(ChunkPos c) {
        return (c.z << 4) + 8;
    }

    private static int groundY(ServerLevel level, ChunkPos c) {
        int x = centerBlockX(c);
        int z = centerBlockZ(c);
        return Math.max(level.getMinBuildHeight() + 1,
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    private static final class Anchor {
        final UUID player;
        final ServerLevel level;
        ChunkPos center;
        int radius;
        boolean spawn;
        boolean ai;
        CameraAnchorVirtualPlayer virtual;

        Anchor(UUID player, ServerLevel level) {
            this.player = player;
            this.level = level;
        }
    }
}
