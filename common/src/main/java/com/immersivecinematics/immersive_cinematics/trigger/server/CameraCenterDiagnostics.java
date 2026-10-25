package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 相机中心服务端诊断（C6）：把「配对 / 刷怪门 / 刷怪尝试 / 实际生成 / 局部上限」按玩家会话分桶，
 * 每 {@value #PRINT_INTERVAL_TICKS} tick 输出一行可 grep 的 ASCII 汇总，供与客户端 {@code [camera-client]}
 * 行按 {@code gameTime} + 相机坐标对账。
 *
 * <p>只读诊断：不改变任何原版 / 模组行为，不写状态、不发包。所有计数只在 {@link Config#debugLogging}
 * 且存在会话时累加；{@code release} / {@code timeout} 属稀有事件，始终 INFO。
 *
 * <p>归因口径（读数前先看这三条）：
 * <ul>
 *   <li>配对计数只统计「会话开始之后」的 {@code addPairing/removePairing}，因此 {@code pairSeen} 是
 *       会话内配对集合大小，不是玩家当前全部已配对实体数：会话开始前已配对、且仍在相机范围内的实体会漏计。</li>
 *   <li>刷怪门 / 刷怪尝试 / 实际生成按「最近的相机锚点」归因（128 格 2D，与原版刷怪门同口径）。</li>
 *   <li>{@code LocalMobCapCalculator} 拿不到维度，局部上限计数按跨维度最近的锚点归因，
 *       不同维度坐标相同时可能误归属；仅诊断用，不能据此判断实际配额归属。</li>
 * </ul>
 */
public final class CameraCenterDiagnostics {

    /** 打印周期（tick）：与客户端探针的 debug 周期一致，便于按 gameTime 对齐两侧日志。 */
    public static final int PRINT_INTERVAL_TICKS = 20;

    /** 每个周期最多输出的明细行数（汇总行不受限）。 */
    private static final int GATE_DETAIL_LIMIT = 3;
    private static final int LOCAL_CAP_DETAIL_LIMIT = 3;
    private static final int SPAWN_DETAIL_LIMIT = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraCenter");

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private CameraCenterDiagnostics() {}

    // ===== 生命周期 =====

    /** 新播放会话开始（在该次 {@code ChunkMap.move} 之前调用）：重置计数与配对集合，首次批量配对也能计入。 */
    public static void onStart(ServerPlayer player, String scriptId, long sessionId) {
        if (!Config.debugLogging) return;
        Session s = new Session(player.getUUID());
        s.scriptId = scriptId;
        s.sessionId = sessionId;
        SESSIONS.put(player.getUUID(), s);
    }

    /** 服务端停止 / 换档：丢弃全部会话，避免静态状态跨存档继承。 */
    public static void clearAll() {
        SESSIONS.clear();
    }

    /** 每 tick 的防御性清理：release 漏掉的离线玩家会话不会永久留存。 */
    public static void onServerTick(MinecraftServer server) {
        if (!Config.debugLogging || SESSIONS.isEmpty()) return;
        if (server.getTickCount() % PRINT_INTERVAL_TICKS != 0) return;
        SESSIONS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
    }

    // ===== 汇总行 =====

    /**
     * 每个打印周期输出一次 {@code state} 行（每玩家），随后输出该会话的 {@code spawn} 汇总行。
     * 调用方每 tick 调一次即可；非打印周期在格式化前返回，不产生字符串分配。
     */
    public static void onState(MinecraftServer server, ServerPlayer player, int moves, long lastMoveAge) {
        if (!Config.debugLogging) return;
        if (server.getTickCount() % PRINT_INTERVAL_TICKS != 0) return;

        Session s = SESSIONS.get(player.getUUID());
        String scriptId = s != null ? s.scriptId : "";
        long sessionId = s != null ? s.sessionId : 0L;

        Vec3 center = CameraVirtualCenterState.cameraCenterFor(player);
        boolean cameraMode = center != null;
        Vec3 cam = cameraMode ? center : player.position();
        double anchorY = CameraAnchorManager.INSTANCE.anchorY(player.getUUID());

        LOGGER.info("[camera-center] state tick={} gameTime={} player={} level={} cam=({}) section=[{},{}] "
                        + "camMode={} anchorY={} script={} gen={} moves={} lastMoveAge={}",
                server.getTickCount(), gameTime(server), player.getUUID(),
                player.level().dimension().location(), fmtVec(cam),
                chunkCoord(cam.x), chunkCoord(cam.z), cameraMode, fmt3(anchorY),
                scriptId, sessionId, moves, lastMoveAge);

        if (s != null) {
            logSpawn(server, s);
        }
    }

    private static void logSpawn(MinecraftServer server, Session s) {
        LOGGER.info("[camera-center] spawn tick={} gameTime={} player={} gateHit={} gateChunks={} attempts={} "
                        + "spawned={} localCapAllow={} localCapReject={} pairs=+{}/-{} pairSeen={}",
                server.getTickCount(), gameTime(server), s.player,
                s.gateHit, s.gateChunks.size(), s.attempts, s.spawned,
                s.localCapAllow, s.localCapReject, s.pairAdd, s.pairRemove, s.paired.size());
        s.gateHit = 0;
        s.gateChunks.clear();
        s.attempts = 0;
        s.spawned = 0;
        s.localCapAllow = 0;
        s.localCapReject = 0;
        s.pairAdd = 0;
        s.pairRemove = 0;
        s.gateDetail = 0;
        s.localCapDetail = 0;
        s.spawnDetail = 0;
    }

    // ===== 事件行 / 计数 =====

    /** 虚拟中心真正应用到原版 {@code ChunkMap}（reason = level | flip | section | threshold）。 */
    public static void onMove(ServerPlayer player, String reason, int moves, Vec3 center) {
        if (!Config.debugLogging) return;
        LOGGER.info("[camera-center] move player={} reason={} moves={} cam=({}) section=[{},{}]",
                player.getUUID(), reason, moves, fmtVec(center),
                chunkCoord(center.x), chunkCoord(center.z));
    }

    /** 超时兜底（始终 INFO；客户端崩溃 / 断线 / END 丢失时定位用）。 */
    public static void onTimeout(UUID uuid, String scriptId, long sessionId, long age, long limit) {
        LOGGER.info("[camera-center] timeout player={} age={} limit={} script={} gen={}",
                uuid, age, limit, scriptId, sessionId);
    }

    /**
     * 释放（始终 INFO）。{@code hadState == false} 时只有 debug 才输出，避免重复 END / 断线二次释放刷屏。
     * 无论是否输出都丢弃该玩家的诊断会话。
     */
    public static void onRelease(UUID uuid, String scriptId, long sessionId, String reason,
                                 int remaining, boolean hadState) {
        SESSIONS.remove(uuid);
        if (!hadState && !Config.debugLogging) return;
        LOGGER.info("[camera-center] release player={} script={} gen={} reason={} remaining={}",
                uuid, scriptId, sessionId, reason, remaining);
    }

    /** 刷怪门命中（{@code anyPlayerCloseEnoughForSpawning} 被相机锚点放行）。 */
    public static void onSpawnGateHit(ServerLevel level, ChunkPos chunkPos) {
        if (!Config.debugLogging || SESSIONS.isEmpty()) return;
        UUID owner = CameraAnchorManager.INSTANCE.ownerNear(level, chunkPos);
        if (owner == null) return;
        Session s = SESSIONS.get(owner);
        if (s == null) return;
        s.gateHit++;
        s.gateChunks.add(chunkPos.toLong());
        if (s.gateDetail >= GATE_DETAIL_LIMIT) return;
        Vec3 center = CameraAnchorManager.INSTANCE.anchorCenter(owner);
        if (center == null) return;
        s.gateDetail++;
        double dx = (chunkPos.x << 4) + 8 - center.x;
        double dz = (chunkPos.z << 4) + 8 - center.z;
        LOGGER.info("[camera-center] spawn gate chunk=[{},{}] hit=true src=camera d2cam={}",
                chunkPos.x, chunkPos.z, fmt3(dx * dx + dz * dz));
    }

    /** 相机影响范围内的一次 {@code spawnCategoryForChunk} 调用。 */
    public static void onSpawnAttempt(ServerLevel level, ChunkPos chunkPos) {
        if (!Config.debugLogging || SESSIONS.isEmpty()) return;
        UUID owner = CameraAnchorManager.INSTANCE.ownerNear(level, chunkPos);
        if (owner == null) return;
        Session s = SESSIONS.get(owner);
        if (s != null) {
            s.attempts++;
        }
    }

    /** 实际生成（{@code SpawnState.afterSpawn}）。 */
    public static void onSpawned(Mob mob) {
        if (!Config.debugLogging || SESSIONS.isEmpty()) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        ChunkPos chunkPos = mob.chunkPosition();
        UUID owner = CameraAnchorManager.INSTANCE.ownerNear(level, chunkPos);
        if (owner == null) return;
        Session s = SESSIONS.get(owner);
        if (s == null) return;
        s.spawned++;
        if (s.spawnDetail >= SPAWN_DETAIL_LIMIT) return;
        s.spawnDetail++;
        Vec3 center = CameraAnchorManager.INSTANCE.anchorCenter(owner);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        double d2cam = center != null ? mob.distanceToSqr(center) : Double.NaN;
        double d2player = player != null ? mob.distanceToSqr(player) : Double.NaN;
        LOGGER.info("[camera-center] spawned type={} pos=({},{},{}) d2cam={} d2player={}",
                BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()),
                fmt1(mob.getX()), fmt1(mob.getY()), fmt1(mob.getZ()), fmt3(d2cam), fmt3(d2player));
    }

    /** 局部上限判定（{@code LocalMobCapCalculator.canSpawn} 的返回值）。 */
    public static void onLocalCap(boolean allow, ChunkPos chunkPos, ChunkMap chunkMap) {
        if (!Config.debugLogging || SESSIONS.isEmpty()) return;
        UUID owner = CameraAnchorManager.INSTANCE.ownerNearAnyLevel(chunkPos);
        if (owner == null) return;
        Session s = SESSIONS.get(owner);
        if (s == null) return;
        if (allow) {
            s.localCapAllow++;
        } else {
            s.localCapReject++;
        }
        if (s.localCapDetail >= LOCAL_CAP_DETAIL_LIMIT) return;
        s.localCapDetail++;
        int players = chunkMap.getPlayersCloseForSpawning(chunkPos).size();
        LOGGER.info("[camera-center] localCap allow={} chunk=[{},{}] players={}",
                allow, chunkPos.x, chunkPos.z, players);
    }

    /** 实体配对增删（{@code ServerEntity.addPairing/removePairing}），只计入当前存在会话的玩家。 */
    public static void onPair(Entity entity, ServerPlayer player, boolean added) {
        if (!Config.debugLogging || SESSIONS.isEmpty()) return;
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) return;
        int id = entity.getId();
        if (added) {
            if (s.paired.add(id)) {
                s.pairAdd++;
            }
        } else if (s.paired.remove(id)) {
            s.pairRemove++;
        }
    }

    // ===== 格式化 =====

    private static long gameTime(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld != null ? overworld.getGameTime() : 0L;
    }

    private static int chunkCoord(double coord) {
        return Mth.floor(coord) >> 4;
    }

    private static String fmtVec(Vec3 v) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z);
    }

    private static String fmt3(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String fmt1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 单玩家诊断会话；{@code paired} 跨周期保留，其余计数每个打印周期清零。 */
    private static final class Session {
        final UUID player;
        String scriptId = "";
        long sessionId;
        final IntOpenHashSet paired = new IntOpenHashSet();
        final LongOpenHashSet gateChunks = new LongOpenHashSet();
        int gateHit;
        int attempts;
        int spawned;
        int localCapAllow;
        int localCapReject;
        int pairAdd;
        int pairRemove;
        int gateDetail;
        int localCapDetail;
        int spawnDetail;

        Session(UUID player) {
            this.player = player;
        }
    }
}
