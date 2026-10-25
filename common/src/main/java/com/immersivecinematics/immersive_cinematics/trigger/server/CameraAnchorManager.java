package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 相机锚点管理：只记录“哪个玩家、哪个维度、相机中心在哪”，供刷怪门 / 最近玩家 / 消失判定查询，
 * 不创建也不借用任何 Player 实体，不进入世界玩家列表。锚点 y 就是相机眼位 y（与统一位置源一致）；
 * 128 格资格过滤保持原版区块门的 2D 口径，3D 距离判定交给原版。
 */
public final class CameraAnchorManager {

    public static final CameraAnchorManager INSTANCE = new CameraAnchorManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraAnchor");

    private final Map<UUID, Anchor> anchors = new HashMap<>();

    private CameraAnchorManager() {}

    /** 写入/更新相机锚点；同一玩家同一维度复用同一个虚拟玩家实例，只 moveTo。 */
    public void setAnchor(UUID player, ServerLevel level, Vec3 center) {
        Anchor a = anchors.get(player);
        if (a != null && a.level != level) {
            removeAnchor(player);
            a = null;
        }
        if (a == null) {
            a = new Anchor(level);
            anchors.put(player, a);
        }
        a.center = center;
        if (a.virtual == null) {
            a.virtual = new CameraAnchorVirtualPlayer(level, center.x, center.y, center.z);
        } else {
            a.virtual.moveTo(center.x, center.y, center.z, 0.0F, 0.0F);
        }
        if (Config.debugLogging) {
            LOGGER.info("[camera-anchor] 锚点更新 玩家={} 中心=({},{},{})", player, center.x, center.y, center.z);
        }
    }

    public void removeAnchor(UUID player) {
        Anchor a = anchors.remove(player);
        if (a != null && Config.debugLogging) {
            LOGGER.info("[camera-anchor] 锚点移除 玩家={}", player);
        }
    }

    public boolean hasAnchor(UUID player) {
        return anchors.containsKey(player);
    }

    /** 服务端停止 / 换档时的防御性全清。 */
    public void clearAll() {
        anchors.clear();
    }

    /** 维度卸载：移除该维度的锚点，不触碰其它维度。 */
    public void removeForLevel(ServerLevel level) {
        anchors.values().removeIf(a -> a.level == level);
    }

    /** 刷怪 mixin 查询：某区块附近是否存在相机锚点（区块中心到相机 x/z 的 2D 距离 < 128 格，同原版刷怪门） */
    public boolean isAnyAnchorNear(ServerLevel level, ChunkPos chunkPos) {
        for (Anchor a : anchors.values()) {
            if (a.level != level || a.center == null) continue;
            double dx = (chunkPos.x << 4) + 8 - a.center.x;
            double dz = (chunkPos.z << 4) + 8 - a.center.z;
            if (dx * dx + dz * dz < 16384.0) {
                return true;
            }
        }
        return false;
    }

    /** 锚点相机 y；无锚点返回 {@link Double#NaN}（诊断用）。 */
    public double anchorY(UUID player) {
        Anchor a = anchors.get(player);
        return a != null && a.center != null ? a.center.y : Double.NaN;
    }

    /** 锚点中心；无锚点返回 null（诊断用，只读）。 */
    public Vec3 anchorCenter(UUID player) {
        Anchor a = anchors.get(player);
        return a != null ? a.center : null;
    }

    /** 该维度内距区块中心最近的锚点属主（128 格 2D，同原版刷怪门）；无则 null。诊断用。 */
    public UUID ownerNear(ServerLevel level, ChunkPos chunkPos) {
        UUID best = null;
        double bestSq = 16384.0;
        for (Map.Entry<UUID, Anchor> entry : anchors.entrySet()) {
            Anchor a = entry.getValue();
            if (a.level != level || a.center == null) continue;
            double dx = (chunkPos.x << 4) + 8 - a.center.x;
            double dz = (chunkPos.z << 4) + 8 - a.center.z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestSq) {
                bestSq = d2;
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * 跨维度最近的锚点属主。仅给拿不到维度的 {@code LocalMobCapCalculator} 诊断归因用：
     * 不同维度坐标相同时会误归属，不能用于任何行为判定。
     */
    public UUID ownerNearAnyLevel(ChunkPos chunkPos) {
        UUID best = null;
        double bestSq = 16384.0;
        for (Map.Entry<UUID, Anchor> entry : anchors.entrySet()) {
            Anchor a = entry.getValue();
            if (a.center == null) continue;
            double dx = (chunkPos.x << 4) + 8 - a.center.x;
            double dz = (chunkPos.z << 4) + 8 - a.center.z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestSq) {
                bestSq = d2;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** 给 NaturalSpawner / Mob.checkDespawn 返回最近的合格虚拟玩家；没有锚点则返回 null */
    public Player getVirtualPlayer(ServerLevel level, BlockPos pos) {
        Anchor best = null;
        double bestSq = Double.MAX_VALUE;
        for (Anchor a : anchors.values()) {
            if (a.level != level || a.virtual == null || a.center == null) continue;
            double dx = pos.getX() - a.center.x;
            double dz = pos.getZ() - a.center.z;
            if (dx * dx + dz * dz >= 16384.0) continue;
            double d2 = a.virtual.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
            if (d2 < bestSq) {
                bestSq = d2;
                best = a;
            }
        }
        return best != null ? best.virtual : null;
    }

    private static final class Anchor {
        final ServerLevel level;
        Vec3 center;
        CameraAnchorVirtualPlayer virtual;

        Anchor(ServerLevel level) {
            this.level = level;
        }
    }
}
