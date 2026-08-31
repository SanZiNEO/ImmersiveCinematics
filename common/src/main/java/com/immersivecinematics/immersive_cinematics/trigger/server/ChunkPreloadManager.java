package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPreloadResultPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 区块预加载（服务端）— 原版 diff 思路。
 * <p>
 * 不做 far/near 状态机：每次相机中心变化时，用原版同款 {@link ChunkMap#isChunkInRange(int,int,int,int,int)}
 * 计算“相机视距应覆盖”的集合，减去“玩家原版已覆盖”的集合，再和“我们已持票集合”做差集：
 * <ul>
 *   <li>缺失的排队加 ticket</li>
 *   <li>不再需要的立即撤 ticket</li>
 * </ul>
 * 我们持有的 ticket 就是“已请求”的事实来源，不需要额外查询服务端加载状态。
 */
public final class ChunkPreloadManager {

    public static final ChunkPreloadManager INSTANCE = new ChunkPreloadManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ChunkPreload");

    /** 每个玩家独立的相机区 ticket key：key = player UUID + chunkPos。 */
    private static final TicketType<String> TICKET =
            TicketType.<String>create("immersive_cinematics_camera", Comparator.naturalOrder());

    private final Map<UUID, PlayerState> states = new HashMap<>();
    private long lastStatusLog = 0;
    private long lastEntityCountTime = 0;
    private int cachedPlayerEntities = 0;
    private int cachedCameraEntities = 0;

    private ChunkPreloadManager() {}

    public void handleRequest(ServerPlayer player, int mode, String scriptId, int x, int z, int radius, float yaw, int renderDistance,
                              boolean cameraMobSpawn, int cameraMobRadius, boolean cameraMobAi) {
        if (!Config.preloadEnabled) {
            S2CPreloadResultPacket.send(player, scriptId, "预加载全局已关闭");
            return;
        }
        UUID uuid = player.getUUID();
        if (mode == C2SPreloadRequestPacket.MODE_RELEASE) {
            release(uuid, player);
            return;
        }
        if (mode == C2SPreloadRequestPacket.MODE_PRELOAD || mode == C2SPreloadRequestPacket.MODE_PREWARM) {
            CinematicScript preloadScript = ScriptManager.INSTANCE.getScript(scriptId);
            if (preloadScript != null && !hasCameraTrack(preloadScript)) {
                S2CPreloadResultPacket.send(player, scriptId, "脚本无CAMERA轨道，跳过预加载");
                return;
            }
        }
        if (mode == C2SPreloadRequestPacket.MODE_PREWARM) {
            handlePrewarm(uuid, player, scriptId, x, z, radius, renderDistance);
            return;
        }
        PlayerState st = states.computeIfAbsent(uuid, k -> new PlayerState());
        if (!st.scriptId.equals(scriptId)) {
            clearPrewarm(player, st);
        }
        st.player = player;
        st.cameraMobSpawn = cameraMobSpawn;
        st.cameraMobRadius = Math.max(1, Math.min(16, cameraMobRadius));
        st.cameraMobAi = cameraMobAi;
        st.cameraTicketDistance = cameraMobAi ? 2 : 1;
        st.scriptId = scriptId;
        st.playerRenderDistance = renderDistance;
        st.cameraYaw = yaw;

        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.center = cam;
        st.playerChunk = new ChunkPos(player.blockPosition());
        st.regionRadius = computeRegionRadius(st);
        if (player.level() instanceof ServerLevel serverLevel) {
            updateAnchors(uuid, serverLevel, cam, st);
        }
        updateCameraDiff(player, st);
        sendCenter(player, cam);
    }

    /** 只有非空 CAMERA 轨道才允许预加载；纯 OVERLAY/EVENT 脚本由服务端兜底拒绝 */
    private static boolean hasCameraTrack(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        return track.isPresent() && !track.get().getClips().isEmpty();
    }

    /** 下一片段预热：只更新预热目标/待加载队列，不动当前相机差集与客户端中心。 */
    private void handlePrewarm(UUID uuid, ServerPlayer player, String scriptId, int x, int z, int radius, int renderDistance) {
        PlayerState st = states.computeIfAbsent(uuid, k -> new PlayerState());
        st.player = player;
        st.scriptId = scriptId;
        if (renderDistance > 0) st.playerRenderDistance = renderDistance;
        if (st.playerChunk == null) st.playerChunk = new ChunkPos(player.blockPosition());
        st.regionRadius = computeRegionRadius(st);

        clearPrewarm(player, st);

        ChunkPos target = new ChunkPos(x >> 4, z >> 4);
        st.prewarmCenter = target;
        int r = Math.max(2, radius > 0 ? radius : Config.preloadPrewarmRadius);
        if (st.regionRadius > 0) r = Math.min(r, st.regionRadius);
        st.prewarmRadius = r;

        Set<ChunkPos> desired = computeDesiredSet(target, r);
        Set<ChunkPos> playerCovered = computePlayerCoveredSet(st);
        List<ChunkPos> pending = new ArrayList<>();
        for (ChunkPos pos : desired) {
            if (!playerCovered.contains(pos) && !st.cameraZone.contains(pos)) {
                pending.add(pos);
            }
        }
        st.pendingPrewarmChunks = pending;
        sortPrewarmPending(st);
    }

    /**
     * 相机位置上报：更新相机/玩家中心，跑一次差集加载。
     */
    public void handlePosition(ServerPlayer player, int x, int z, float yaw) {
        PlayerState st = states.get(player.getUUID());
        if (st == null) return;
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.cameraYaw = yaw;
        st.center = cam;
        st.playerChunk = new ChunkPos(player.blockPosition());
        if (player.level() instanceof ServerLevel serverLevel) {
            updateAnchors(player.getUUID(), serverLevel, cam, st);
        }
        updateCameraDiff(player, st);
        sendCenter(player, cam);
    }

    /** 由 ServerEventHandler 服务端 tick 调用 */
    public void tick() {
        long now = System.currentTimeMillis();
        for (PlayerState st : states.values()) {
            if (st.player == null || st.center == null) continue;
            tickCameraZoneTickets(st);
            tickPrewarmTickets(st);
            tickCameraChunkSend(st);
        }
        if (Config.debugLogging && now - lastStatusLog >= 1000 && !states.isEmpty()) {
            lastStatusLog = now;
            for (PlayerState st : states.values()) {
                if (st.player == null || st.center == null) continue;
                if (now - st.lastEntityCountTime >= 5000) {
                    st.lastEntityCountTime = now;
                    st.cachedPlayerEntities = 0;
                    st.cachedCameraEntities = 0;
                    if (st.player.level() instanceof ServerLevel serverLevel) {
                        if (st.playerChunk != null) {
                            st.cachedPlayerEntities = countEntities(serverLevel, st.playerChunk, st.regionRadius);
                        }
                        if (st.center != null) {
                            st.cachedCameraEntities = countEntities(serverLevel, st.center, st.regionRadius);
                        }
                    }
                }
                LOGGER.info("[preload status] 玩家={} 坐标=({},{},{}) 维度={} 中心={} 玩家块={} 半径={} 已持票={} 待加={} 已发包={} 玩家区已载={} 玩家实体={} 相机实体={} 预热点={} 预热持票={} 预热待加={}",
                        st.player.getName().getString(),
                        String.format("%.1f", st.player.getX()),
                        String.format("%.1f", st.player.getY()),
                        String.format("%.1f", st.player.getZ()),
                        st.player.level().dimension().location(),
                        st.center != null ? fmt(st.center) : "null",
                        st.playerChunk != null ? fmt(st.playerChunk) : "null",
                        st.regionRadius,
                        st.cameraZone.size(),
                        st.pendingCameraChunks.size(),
                        st.sentCameraChunks.size(),
                        countLoadedPlayerArea(st),
                        st.cachedPlayerEntities, st.cachedCameraEntities,
                        st.prewarmCenter != null ? fmt(st.prewarmCenter) : "null",
                        st.prewarmZone.size(),
                        st.pendingPrewarmChunks.size());
            }
        }
    }

    /** 玩家断线清理 */
    public void onDisconnect(UUID uuid, ServerPlayer player) {
        release(uuid, player);
    }

    /** 客户端上报脚本结束 → 强制释放 */
    public void onScriptFinished(ServerPlayer player) {
        release(player.getUUID(), player);
    }

    public int getPlayerViewDistanceChunks(UUID player) {
        PlayerState st = states.get(player);
        return viewDistanceChunks(st);
    }

    // ===== 原版 diff 核心 =====

    /**
     * 相机/玩家中心变化后执行：
     * desired - playerCovered - requested = 待加；
     * requested - desired = 待撤。
     */
    private void updateCameraDiff(ServerPlayer p, PlayerState st) {
        if (st.center == null || st.playerChunk == null || st.regionRadius <= 0) return;
        Set<ChunkPos> desired = computeDesiredSet(st.center, st.regionRadius);
        st.cameraWindow = desired;

        // 预热区晋级：已持票的预热块若已进入当前相机窗口，直接并入 cameraZone（不重复加票）
        promotePrewarm(st, desired);

        // 玩家原版视距已覆盖：不需要我们加票
        Set<ChunkPos> playerCovered = computePlayerCoveredSet(st);

        // 待加 = desired - playerCovered - cameraZone
        List<ChunkPos> toAdd = new ArrayList<>();
        for (ChunkPos pos : desired) {
            if (!playerCovered.contains(pos) && !st.cameraZone.contains(pos)) {
                toAdd.add(pos);
            }
        }
        st.pendingCameraChunks = toAdd;
        sortPending(st);

        // 待撤 = cameraZone - desired
        if (p.level() instanceof ServerLevel serverLevel) {
            for (ChunkPos pos : new HashSet<>(st.cameraZone)) {
                if (!desired.contains(pos)) {
                    serverLevel.getChunkSource().removeRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(p.getUUID(), pos));
                    st.cameraZone.remove(pos);
                }
            }
        }
    }

    /** 把已进入当前相机窗口的预热块并入 cameraZone；未加票的预热待加块交给相机差集处理 */
    private void promotePrewarm(PlayerState st, Set<ChunkPos> desired) {
        if (st.prewarmCenter == null) return;
        java.util.Iterator<ChunkPos> pendingIt = st.pendingPrewarmChunks.iterator();
        while (pendingIt.hasNext()) {
            ChunkPos pos = pendingIt.next();
            if (desired.contains(pos)) {
                pendingIt.remove();
            }
        }
        java.util.Iterator<ChunkPos> zoneIt = st.prewarmZone.iterator();
        while (zoneIt.hasNext()) {
            ChunkPos pos = zoneIt.next();
            if (desired.contains(pos)) {
                zoneIt.remove();
                // ticket 已存在（复用同一个 cameraTicketKey），直接并入当前相机区
                st.cameraZone.add(pos);
            }
        }
    }

    /** 清除该玩家所有未晋级的预热 ticket/状态（替换预热目标或释放时调用） */
    private void clearPrewarm(ServerPlayer p, PlayerState st) {
        if (st.prewarmCenter == null && st.prewarmZone.isEmpty()) return;
        if (p.level() instanceof ServerLevel serverLevel) {
            for (ChunkPos pos : new HashSet<>(st.prewarmZone)) {
                serverLevel.getChunkSource().removeRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(p.getUUID(), pos));
            }
        }
        st.prewarmZone.clear();
        st.pendingPrewarmChunks.clear();
        st.prewarmCenter = null;
        st.prewarmRadius = 0;
    }

    /** 原版圆形视距集合：与 ChunkMap.isChunkInRange 完全一致 */
    private Set<ChunkPos> computeDesiredSet(ChunkPos center, int r) {
        Set<ChunkPos> set = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                if (ChunkMap.isChunkInRange(pos.x, pos.z, center.x, center.z, r)) {
                    set.add(pos);
                }
            }
        }
        return set;
    }

    /** 玩家当前原版加载范围（以玩家 chunk 为中心） */
    private Set<ChunkPos> computePlayerCoveredSet(PlayerState st) {
        Set<ChunkPos> set = new HashSet<>();
        if (st.playerChunk == null) return set;
        int r = st.regionRadius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(st.playerChunk.x + dx, st.playerChunk.z + dz);
                if (ChunkMap.isChunkInRange(pos.x, pos.z, st.playerChunk.x, st.playerChunk.z, r)) {
                    set.add(pos);
                }
            }
        }
        return set;
    }

    // ===== 相机区块票 =====

    /** 每 tick 按预算从待加队列加票（前向/近处先加） */
    private void tickCameraZoneTickets(PlayerState st) {
        if (st.pendingCameraChunks == null || st.pendingCameraChunks.isEmpty()) return;
        if (!(st.player.level() instanceof ServerLevel serverLevel)) return;
        int budget = Math.max(1, Config.preloadMaxRequestsPerTick);
        java.util.Iterator<ChunkPos> it = st.pendingCameraChunks.iterator();
        int added = 0;
        while (it.hasNext() && added < budget) {
            ChunkPos pos = it.next();
            it.remove();
            if (st.cameraZone.contains(pos)) continue;
            serverLevel.getChunkSource().addRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(st.player.getUUID(), pos));
            st.cameraZone.add(pos);
            added++;
        }
    }

    /** 预热区加票：只提前加载服务端区块，不补发、不切客户端中心 */
    private void tickPrewarmTickets(PlayerState st) {
        if (st.prewarmCenter == null || st.pendingPrewarmChunks == null || st.pendingPrewarmChunks.isEmpty()) return;
        if (!(st.player.level() instanceof ServerLevel serverLevel)) return;
        int budget = Math.max(1, Config.preloadPrewarmRequestsPerTick);
        java.util.Iterator<ChunkPos> it = st.pendingPrewarmChunks.iterator();
        int added = 0;
        while (it.hasNext() && added < budget) {
            ChunkPos pos = it.next();
            it.remove();
            if (st.prewarmZone.contains(pos) || st.cameraZone.contains(pos)) continue;
            serverLevel.getChunkSource().addRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(st.player.getUUID(), pos));
            st.prewarmZone.add(pos);
            added++;
        }
    }

    /** 相机区区块包补发：只发我们持票且服务端已加载的区块；离开 desired 的已发区块发 forget */
    private void tickCameraChunkSend(PlayerState st) {
        ServerPlayer p = st.player;
        if (p == null || p.connection == null || st.center == null) return;
        if (!(p.level() instanceof ServerLevel serverLevel)) return;
        int burstCap = Math.max(1, Config.preloadMaxBurstPerTick);
        int sent = 0;
        // 以“相机应覆盖集合”为准，而不是“我们额外持票集合”：
        // 玩家已覆盖但客户端中心切走后可能已被丢弃的区块，也必须补发。
        for (ChunkPos pos : st.cameraWindow) {
            if (sent >= burstCap) break;
            if (!st.sentCameraChunks.contains(pos) && serverLevel.getChunkSource().hasChunk(pos.x, pos.z)) {
                LevelChunk chunk = serverLevel.getChunk(pos.x, pos.z);
                p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, serverLevel.getLightEngine(), null, null));
                st.sentCameraChunks.add(pos);
                sent++;
            }
        }
        java.util.Iterator<ChunkPos> it = st.sentCameraChunks.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            if (!st.cameraWindow.contains(pos)) {
                p.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
                it.remove();
            }
        }
    }

    // ===== 玩家区差集补发（释放/结束时） =====

    private void resyncPlayerAreaDiff(ServerPlayer p, PlayerState st, Set<ChunkPos> reusable) {
        if (st.playerChunk == null || st.regionRadius <= 0) return;
        if (!(p.level() instanceof ServerLevel serverLevel)) return;
        int r = st.regionRadius;
        ChunkPos pc = st.playerChunk;
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(pc.x + dx, pc.z + dz);
                if (reusable.contains(pos)) continue;
                if (!serverLevel.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                LevelChunk chunk = serverLevel.getChunk(pos.x, pos.z);
                p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, serverLevel.getLightEngine(), null, null));
                count++;
            }
        }
        LOGGER.info("[preload resync] 玩家={} 玩家区差集补发 {} 块（可复用 {}）", p.getName().getString(), count, reusable.size());
    }

    // ===== 统计 =====

    private int countEntities(ServerLevel level, ChunkPos center, int radius) {
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                if (!level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                AABB box = new AABB(
                        pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                        pos.getMaxBlockX() + 1.0, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1.0);
                count += level.getEntities((Entity) null, box, e -> true).size();
            }
        }
        return count;
    }

    private int countLoadedPlayerArea(PlayerState st) {
        if (st.player == null || st.playerChunk == null || st.regionRadius <= 0) return 0;
        if (!(st.player.level() instanceof ServerLevel serverLevel)) return 0;
        int r = st.regionRadius;
        ChunkPos pc = st.playerChunk;
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(pc.x + dx, pc.z + dz);
                if (serverLevel.getChunkSource().hasChunk(pos.x, pos.z)) count++;
            }
        }
        return count;
    }

    // ===== 辅助 =====

    private void sortPending(PlayerState st) {
        List<ChunkPos> pending = st.pendingCameraChunks;
        if (pending == null || pending.isEmpty() || st.center == null) return;
        double fx = -Math.sin(Math.toRadians(st.cameraYaw));
        double fz = Math.cos(Math.toRadians(st.cameraYaw));
        int cx = (st.center.x << 4) + 8;
        int cz = (st.center.z << 4) + 8;
        pending.sort((a, b) -> {
            double adx = (a.x << 4) + 8 - cx;
            double adz = (a.z << 4) + 8 - cz;
            double bdx = (b.x << 4) + 8 - cx;
            double bdz = (b.z << 4) + 8 - cz;
            boolean af = adx * fx + adz * fz >= 0;
            boolean bf = bdx * fx + bdz * fz >= 0;
            if (af != bf) return af ? -1 : 1;
            double ad = adx * adx + adz * adz;
            double bd = bdx * bdx + bdz * bdz;
            return Double.compare(ad, bd);
        });
    }

    private void sortPrewarmPending(PlayerState st) {
        List<ChunkPos> pending = st.pendingPrewarmChunks;
        if (pending == null || pending.isEmpty() || st.prewarmCenter == null) return;
        int cx = (st.prewarmCenter.x << 4) + 8;
        int cz = (st.prewarmCenter.z << 4) + 8;
        pending.sort((a, b) -> {
            double adx = (a.x << 4) + 8 - cx;
            double adz = (a.z << 4) + 8 - cz;
            double bdx = (b.x << 4) + 8 - cx;
            double bdz = (b.z << 4) + 8 - cz;
            double ad = adx * adx + adz * adz;
            double bd = bdx * bdx + bdz * bdz;
            return Double.compare(ad, bd);
        });
    }

    private void sendCenter(ServerPlayer p, ChunkPos c) {
        p.connection.send(new ClientboundSetChunkCacheCenterPacket(c.x, c.z));
    }

    /** 释放时只忘记玩家不需要的相机区块；keep 内的块保留在客户端，避免近距离结束重发玩家区 */
    private void forgetCameraChunks(ServerPlayer p, PlayerState st, Set<ChunkPos> keep) {
        if (p == null || p.connection == null) return;
        for (ChunkPos pos : new HashSet<>(st.sentCameraChunks)) {
            if (!keep.contains(pos)) {
                p.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
                st.sentCameraChunks.remove(pos);
            }
        }
    }

    private static String cameraTicketKey(UUID player, ChunkPos pos) {
        return player + "|" + pos.x + "," + pos.z;
    }

    private static String fmt(ChunkPos c) {
        return "[" + c.x + ", " + c.z + "]";
    }

    // ===== 释放 =====

    private void release(UUID uuid, ServerPlayer p) {
        PlayerState st = states.remove(uuid);
        CameraAnchorManager.INSTANCE.removeAnchor(uuid);
        CameraEntitySyncManager.INSTANCE.removeAnchor(uuid);
        if (st == null) return;
        if (!(p.level() instanceof ServerLevel serverLevel)) return;
        for (ChunkPos pos : new HashSet<>(st.cameraZone)) {
            serverLevel.getChunkSource().removeRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(uuid, pos));
        }
        for (ChunkPos pos : new HashSet<>(st.prewarmZone)) {
            serverLevel.getChunkSource().removeRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(uuid, pos));
        }
        st.cameraZone.clear();
        st.pendingCameraChunks.clear();
        st.prewarmZone.clear();
        st.pendingPrewarmChunks.clear();
        st.prewarmCenter = null;
        st.prewarmRadius = 0;

        // 释放差集复用：只忘掉玩家不需要的相机块，只补发玩家需要且相机没发过的块
        Set<ChunkPos> playerNeed = st.playerChunk != null ? computePlayerCoveredSet(st) : new HashSet<>();
        Set<ChunkPos> reusable = new HashSet<>(st.sentCameraChunks);
        reusable.retainAll(playerNeed);
        forgetCameraChunks(p, st, playerNeed);
        if (st.playerChunk != null) {
            sendCenter(p, st.playerChunk);
            resyncPlayerAreaDiff(p, st, reusable);
        }
        LOGGER.info("预加载释放: 玩家 {}（玩家区需要 {}，可复用 {}）", p.getName().getString(), playerNeed.size(), reusable.size());
    }

    /** 更新相机锚点 + 实体同步器（无假人方案） */
    private void updateAnchors(UUID player, ServerLevel level, ChunkPos cam, PlayerState st) {
        CameraAnchorManager.INSTANCE.setAnchor(player, level, cam,
                st.cameraMobRadius, st.cameraMobSpawn, st.cameraMobAi);
        CameraEntitySyncManager.INSTANCE.setAnchor(player, level, cam, st.cameraMobRadius);
    }

    // ===== 距离/半径 =====

    private int viewDistanceChunks(PlayerState st) {
        if (st == null || st.player == null) return 2;
        int serverRd = Math.max(2, st.player.server.getPlayerList().getViewDistance());
        int playerRd = st.playerRenderDistance > 0 ? Math.max(2, st.playerRenderDistance) : serverRd;
        return Math.min(playerRd, serverRd);
    }

    private int computeRegionRadius(PlayerState st) {
        int serverRd = Math.max(2, st.player.server.getPlayerList().getViewDistance());
        int cap = Math.max(1, Config.preloadRadiusCap);
        if (Config.preloadForceRadius) {
            return Math.min(Math.max(2, Config.preloadForceRadiusValue), cap);
        }
        int playerRd = st.playerRenderDistance > 0 ? Math.max(2, st.playerRenderDistance) : serverRd;
        return Math.min(Math.min(playerRd, serverRd), cap);
    }

    /** 单个玩家的预加载状态 */
    private static final class PlayerState {
        ServerPlayer player;
        String scriptId = "";
        float cameraYaw = 0;
        int playerRenderDistance = 0;
        ChunkPos center;
        ChunkPos playerChunk;
        int regionRadius = 2;
        boolean cameraMobSpawn = false;
        int cameraMobRadius = 2;
        boolean cameraMobAi = false;
        int cameraTicketDistance = 1;
        long lastEntityCountTime = 0;
        int cachedPlayerEntities = 0;
        int cachedCameraEntities = 0;
        Set<ChunkPos> cameraWindow = new HashSet<>();
        Set<ChunkPos> cameraZone = new HashSet<>();
        List<ChunkPos> pendingCameraChunks = new ArrayList<>();
        Set<ChunkPos> sentCameraChunks = new HashSet<>();

        // 下一片段预热区：只加服务端 ticket，不补发、不切客户端中心
        ChunkPos prewarmCenter;
        int prewarmRadius = 0;
        Set<ChunkPos> prewarmZone = new HashSet<>();
        List<ChunkPos> pendingPrewarmChunks = new ArrayList<>();
    }
}
