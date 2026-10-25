package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 相机虚拟中心状态（服务端主线程访问，与 CameraAnchorManager / CameraCenterManager 同线程约定）。
 * 只替换“中心”这一个入口：section（原版 ChunkMap 差集用）与 center（实体配对距离用）在写入时算好，
 * DistanceManager 与客户端发送仍全归原版；记维度是为了切维度后旧中心不被误用（不匹配即未激活）。
 */
public final class CameraVirtualCenterState {

    private CameraVirtualCenterState() {}

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    /** 相机片段激活时写入。blockX / blockZ = 客户端上报的相机坐标。 */
    public static void setCamera(UUID player, ServerLevel level, int blockX, int blockZ) {
        ENTRIES.put(player, new Entry(level,
                SectionPos.of(new ChunkPos(blockX >> 4, blockZ >> 4), 0),
                new Vec3(blockX, 0.0, blockZ)));
    }

    /** 相机片段结束/释放时清除。 */
    public static void clearCamera(UUID player) {
        ENTRIES.remove(player);
    }

    /** 该玩家在当前维度是否处于相机中心模式；是则返回相机所在 section，否则 null。 */
    public static SectionPos cameraSectionFor(ServerPlayer player) {
        Entry e = ENTRIES.get(player.getUUID());
        return e != null && e.level == player.level() ? e.section : null;
    }

    /** 相机坐标（y 固定 0，唯一的消费方只读 x/z）；非相机中心模式返回 null。 */
    public static Vec3 cameraCenterFor(ServerPlayer player) {
        Entry e = ENTRIES.get(player.getUUID());
        return e != null && e.level == player.level() ? e.center : null;
    }

    private record Entry(ServerLevel level, SectionPos section, Vec3 center) {}
}
