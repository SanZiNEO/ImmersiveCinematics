package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 相机虚拟中心状态（服务端主线程访问，与 CameraAnchorManager / CameraCenterManager 同线程约定）。
 * 只替换“中心”这一个入口：section（原版 ChunkMap 差集用）与 center（实体配对 / 刷怪门 / 消失判定共用）
 * 在写入时算好，DistanceManager 与客户端发送仍全归原版；记维度是为了切维度后旧中心不被误用（不匹配即未激活）。
 */
public final class CameraVirtualCenterState {

    private CameraVirtualCenterState() {}

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    /** 相机片段激活时写入。x/y/z = 客户端上报的相机眼位；section 只取 x/z（区块差集与 ticket 都是 2D）。 */
    public static void setCamera(UUID player, ServerLevel level, double x, double y, double z) {
        ENTRIES.put(player, new Entry(level,
                SectionPos.of(new ChunkPos(Mth.floor(x) >> 4, Mth.floor(z) >> 4), 0),
                new Vec3(x, y, z)));
    }

    /** 相机片段结束/释放时清除。 */
    public static void clearCamera(UUID player) {
        ENTRIES.remove(player);
    }

    /** 服务端停止 / 换档时的防御性全清；不触碰任何 chunk source。 */
    public static void clearAll() {
        ENTRIES.clear();
    }

    /** 维度卸载：只移除该维度的条目，不触发 move。 */
    public static void clearLevel(ServerLevel level) {
        ENTRIES.values().removeIf(e -> e.level == level);
    }

    /** 该玩家在当前维度是否处于相机中心模式；是则返回相机所在 section，否则 null。 */
    public static SectionPos cameraSectionFor(ServerPlayer player) {
        Entry e = ENTRIES.get(player.getUUID());
        return e != null && e.level == player.level() ? e.section : null;
    }

    /** 相机眼位（含 y）；非相机中心模式返回 null。 */
    public static Vec3 cameraCenterFor(ServerPlayer player) {
        Entry e = ENTRIES.get(player.getUUID());
        return e != null && e.level == player.level() ? e.center : null;
    }

    private record Entry(ServerLevel level, SectionPos section, Vec3 center) {}
}
