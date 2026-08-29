package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPreloadResultPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.MinecraftServer;
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
import java.util.Set;
import java.util.UUID;

/**
 * 区块预加载（服务端）— v7：far 立即进入 + 退出时玩家区对账补发 + 每秒健康日志。
 * <ul>
 *   <li><b>门控</b>：相机在玩家视距外即进入 far-view（无防抖——退出/回玩家靠玩家区对账补发兜底）</li>
 *   <li><b>相机区</b>：范围 = 玩家视距；逐块票 + 每 tick 请求预算 + 发送预算（压力可控）</li>
 *   <li><b>退出/回玩家</b>：撤相机区票、还玩家小块、中心回玩家，并<b>主动补发玩家 ± 视距内已加载区块</b>
 *       （对账补发——原版记账不会重发，必须由我们拆掉空洞）</li>
 *   <li><b>B/C</b>：玩家小块保活；客户端缓存中心=相机，返回/结束回玩家</li>
 * </ul>
 * 全程走 MC 公开 API，不反射、不 try/catch（有错即冒）。
 */
public final class ChunkPreloadManager {

    public static final ChunkPreloadManager INSTANCE = new ChunkPreloadManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ChunkPreload");

    /**
     * 每个玩家独立的相机区 ticket key：key = player UUID + chunkPos。
     * 不能只用 ChunkPos 当 key——否则两个玩家会对同一个区块持有同一个 ticket，
     * 一个人释放会把另一个人的也一起撤掉（多人播放会互相拆台）。
     */
    private static final TicketType<String> TICKET =
            TicketType.<String>create("immersive_cinematics_camera", Comparator.naturalOrder());

    /** 单脚本内保留的旧相机区块上限：超出后从“离当前相机最远”的开始淘汰 */
    private static final int MAX_RETAINED_CHUNKS = 2048;

    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Map<net.minecraft.server.level.ServerLevel, ChunkTicketPool> pools = new HashMap<>();
    private long lastStatusLog = 0;
    private long lastEntityCountTime = 0;
    private int cachedPlayerEntities = 0;
    private int cachedCameraEntities = 0;

    private ChunkTicketPool pool(net.minecraft.server.level.ServerLevel level) {
        return pools.computeIfAbsent(level, k -> new ChunkTicketPool());
    }

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
        PlayerState st = states.computeIfAbsent(uuid, k -> new PlayerState());
        st.player = player;
        st.cameraMobSpawn = cameraMobSpawn;
        st.cameraMobRadius = Math.max(1, Math.min(16, cameraMobRadius));
        st.cameraMobAi = cameraMobAi;
        st.cameraTicketDistance = cameraMobAi ? 2 : 1;
        boolean freshScript = !scriptId.equals(st.scriptId);
        st.scriptId = scriptId;
        if (mode == C2SPreloadRequestPacket.MODE_PREWARM) {
            // 独立 prewarm 已取消：只有一个相机中心，忽略旧协议的预载请求
            return;
        }
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.playerChunk = new ChunkPos(player.blockPosition());
        st.cameraYaw = yaw;
        st.playerRenderDistance = renderDistance;
        if (freshScript) {
            enterFar(player, st, cam); // 全程相机中心：脚本开始即进入相机预载，不再按距离门控
        }
        // 同脚本重入：零介入
    }

    /** 相机位置上报：脚本全程相机中心，中心变化即滑动相机区块窗口并更新客户端缓存中心 */
    public void handlePosition(ServerPlayer player, int x, int z, float yaw) {
        PlayerState st = states.get(player.getUUID());
        if (st == null) return;
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.cameraYaw = yaw;

        // 锚点始终钉在最新相机位置（刷怪/实体同步用）
        if (CameraAnchorManager.INSTANCE.hasAnchor(player.getUUID())) {
            if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                updateAnchors(player.getUUID(), serverLevel, cam, st);
            }
        }

        if (!st.farMode) return; // 未进入预载模式（脚本开始后都会进入）
        if (cam.equals(st.center)) return;

        // 相机区块窗口滑动：先加新窗口票，再释放离开窗口的票
        updateCameraZone(player, st, cam);
        st.center = cam;
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            updateAnchors(player.getUUID(), serverLevel, cam, st);
        }
        sendCenter(player, cam);
    }

    /** 由 ServerEventHandler 服务端 tick 调用 */
    public void tick() {
        long now = System.currentTimeMillis();
        // 相机区块票：按“前向半圆优先 + 由近到远”每 tick 加预算个数的票
        for (PlayerState st : states.values()) {
            if (st.player == null || !st.farMode || st.center == null) continue;
            tickCameraZoneTickets(st);
        }
        // 相机区区块包补发：far 模式下手动把已加载区块发给真实玩家
        for (PlayerState st : states.values()) {
            if (st.player == null || !st.farMode || st.center == null) continue;
            tickCameraChunkSend(st);
        }
        // 健康/诊断日志只在调试模式输出；生产环境不每秒扫区块做实体统计
        if (Config.debugLogging && now - lastStatusLog >= 1000 && !states.isEmpty()) {
            lastStatusLog = now;
            for (PlayerState st : states.values()) {
                if (st.player == null) continue;
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
                LOGGER.info("[preload status] 玩家={} 坐标=({},{},{}) 维度={} far={} 中心={} 玩家块={} 半径={} 相机已加票={} 相机待加={} 已发包={} 玩家区已载={} 玩家实体={} 相机实体={}",
                        st.player.getName().getString(),
                        String.format("%.1f", st.player.getX()),
                        String.format("%.1f", st.player.getY()),
                        String.format("%.1f", st.player.getZ()),
                        st.player.level().dimension().location(),
                        st.farMode,
                        st.center != null ? fmt(st.center) : "null",
                        st.playerChunk != null ? fmt(st.playerChunk) : "null",
                        st.regionRadius,
                        st.cameraZone.size(),
                        st.pendingCameraChunks.size(),
                        st.sentCameraChunks.size(),
                        countLoadedPlayerArea(st),
                        st.cachedPlayerEntities, st.cachedCameraEntities);
            }
        }
    }

    /** 玩家断线清理 */
    public void onDisconnect(UUID uuid, ServerPlayer player) {
        release(uuid, player);
    }

    /** 是否处于 far 模式（相机远离玩家，需要假人接管实体/区块中继） */
    public boolean isFarMode(UUID player) {
        PlayerState st = states.get(player);
        return st != null && st.farMode;
    }

    /** 玩家实际视距（区块数）：客户端渲染距离与服务端视距取小；未知时回退服务端视距 */
    public int getPlayerViewDistanceChunks(UUID player) {
        PlayerState st = states.get(player);
        return viewDistanceChunks(st);
    }

    /** 客户端上报脚本结束（任意退出方式，含强退）→ 强制释放，区块加载交还玩家/原版 */
    public void onScriptFinished(ServerPlayer player) {
        release(player.getUUID(), player);
    }

    // ===== far-view 生命周期 =====

    private void enterFar(ServerPlayer player, PlayerState st, ChunkPos cam) {
        st.playerChunk = new ChunkPos(player.blockPosition());
        st.farMode = true;
        st.center = cam;
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            updateAnchors(player.getUUID(), serverLevel, cam, st);
        }
        MinecraftServer server = player.server;
        // 有效半径：优先玩家渲染距离（与客户端设置一致），服务端视距为上限来源，再受模组 cap 约束；作者可强制预设值
        int serverRd = Math.max(2, server.getPlayerList().getViewDistance());
        int cap = Math.max(1, Config.preloadRadiusCap);
        if (Config.preloadForceRadius) {
            st.regionRadius = Math.min(Math.max(2, Config.preloadForceRadiusValue), cap);
        } else {
            int playerRd = st.playerRenderDistance > 0 ? Math.max(2, st.playerRenderDistance) : serverRd;
            st.regionRadius = Math.min(Math.min(playerRd, serverRd), cap);
        }
        LOGGER.info("[preload far-start] 玩家={} 中心→{} 玩家块={} 区域半径={}（玩家={}/服务端={}/cap={}/强制={}）阈值={}",
                player.getName().getString(), fmt(cam), fmt(st.playerChunk),
                st.regionRadius, st.playerRenderDistance, serverRd, cap,
                Config.preloadForceRadius ? Config.preloadForceRadiusValue : "-",
                viewDistanceChunks(st));
        setCameraZone(player, st, true);
        sendCenter(player, cam);
    }

    private void clearCameraArea(ServerPlayer p, PlayerState st) {
        st.center = null;
        forgetCameraChunks(p, st);
    }

    // ===== 玩家区对账补发（拆掉原版记账脱节的空洞） =====

    private void resyncPlayerArea(ServerPlayer p, PlayerState st) {
        if (st.playerChunk == null || st.regionRadius <= 0) return;
        if (!(p.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
        int r = st.regionRadius;
        ChunkPos pc = st.playerChunk;
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(pc.x + dx, pc.z + dz);
                if (!level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                count++;
            }
        }
        LOGGER.info("[preload resync] 玩家={} 玩家区对账补发 {} 块", p.getName().getString(), count);
    }

    /** 相机区区块包补发：far 模式下手动把已加载区块发给真实玩家，并 forget 离开窗口的区块 */
    private void tickCameraChunkSend(PlayerState st) {
        ServerPlayer p = st.player;
        if (p == null || p.connection == null || st.center == null) return;
        if (!(p.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
        int r = st.regionRadius;
        ChunkPos c = st.center;
        Set<ChunkPos> window = new HashSet<>();
        int sent = 0;
        int burstCap = Math.max(1, Config.preloadMaxBurstPerTick);
        outer:
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                window.add(pos);
                if (!st.sentCameraChunks.contains(pos) && level.getChunkSource().hasChunk(pos.x, pos.z)) {
                    if (sent >= burstCap) break outer;
                    LevelChunk chunk = level.getChunk(pos.x, pos.z);
                    p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                    st.sentCameraChunks.add(pos);
                    sent++;
                }
            }
        }
        // 离开保留池（真正被淘汰/释放）的已发区块：通知客户端 forget。
        // 仍在 cameraZone（当前窗口 + 单脚本保留池）里的区块不 forget，供后续片段复用。
        java.util.Iterator<ChunkPos> it = st.sentCameraChunks.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            if (!st.cameraZone.contains(pos)) {
                p.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
                it.remove();
            }
        }
    }

    /** 清理相机区已发区块记录（far 结束/释放时调用） */
    private void forgetCameraChunks(ServerPlayer p, PlayerState st) {
        if (p == null || p.connection == null) return;
        for (ChunkPos pos : new HashSet<>(st.sentCameraChunks)) {
            p.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
        }
        st.sentCameraChunks.clear();
    }

    /** 统计以 center 为中心、radius 个区块范围内已加载区块里的实体数量 */
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

    /** 每秒健康日志用：玩家 ± 视距内服务端真正已加载（hasChunk）的区块数 */
    private int countLoadedPlayerArea(PlayerState st) {
        if (st.player == null || st.playerChunk == null || st.regionRadius <= 0) return 0;
        if (!(st.player.level() instanceof net.minecraft.server.level.ServerLevel)) return 0;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) st.player.level();
        int r = st.regionRadius;
        ChunkPos pc = st.playerChunk;
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(pc.x + dx, pc.z + dz);
                if (level.getChunkSource().hasChunk(pos.x, pos.z)) count++;
            }
        }
        return count;
    }

    // ===== B 相机区块窗口票券（唯一中心=相机；前向半圆优先，由近到远） =====

    private void setCameraZone(ServerPlayer p, PlayerState st, boolean on) {
        if (on) {
            if (st.center == null) return;
            st.cameraWindow.clear();
            st.pendingCameraChunks.clear();
            int r = st.regionRadius;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    st.cameraWindow.add(new ChunkPos(st.center.x + dx, st.center.z + dz));
                }
            }
            rebuildPending(st);
        } else {
            if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
                net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) p.level();
                for (ChunkPos pos : new HashSet<>(st.cameraZone)) {
                    lv.getChunkSource().removeRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(p.getUUID(), pos));
                }
            }
            st.cameraZone.clear();
            st.cameraWindow.clear();
            st.pendingCameraChunks.clear();
        }
    }

    /** 相机中心滑动：新窗口并入待加队列；旧窗口区块进入保留池不撤票，超出容量再淘汰 */
    private void updateCameraZone(ServerPlayer p, PlayerState st, ChunkPos newCenter) {
        if (st.center == null) {
            st.center = newCenter;
            setCameraZone(p, st, true);
            return;
        }
        int r = st.regionRadius;
        Set<ChunkPos> next = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                next.add(new ChunkPos(newCenter.x + dx, newCenter.z + dz));
            }
        }
        st.cameraWindow.clear();
        st.cameraWindow.addAll(next);
        st.center = newCenter;
        rebuildPending(st);
        evictRetained(p, st);
    }

    /**
     * 单脚本内保留池淘汰：cameraZone 同时包含“当前窗口 + 之前片段保留的旧区块”。
     * 超过 {@link #MAX_RETAINED_CHUNKS} 时，从离当前相机中心最远的非当前窗口区块开始撤票。
     * 当前窗口永远不淘汰。
     */
    private void evictRetained(ServerPlayer p, PlayerState st) {
        if (st.cameraZone.size() <= MAX_RETAINED_CHUNKS) return;
        if (!(p.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) p.level();
        int cx = (st.center.x << 4) + 8;
        int cz = (st.center.z << 4) + 8;

        java.util.List<ChunkPos> evictCandidates = new ArrayList<>();
        for (ChunkPos pos : st.cameraZone) {
            if (st.cameraWindow.contains(pos)) continue;
            double dx = (pos.x << 4) + 8 - cx;
            double dz = (pos.z << 4) + 8 - cz;
            evictCandidates.add(pos);
        }
        evictCandidates.sort((a, b) -> {
            double adx = (a.x << 4) + 8 - cx;
            double adz = (a.z << 4) + 8 - cz;
            double bdx = (b.x << 4) + 8 - cx;
            double bdz = (b.z << 4) + 8 - cz;
            return Double.compare(bdx * bdx + bdz * bdz, adx * adx + adz * adz);
        });

        int toRemove = st.cameraZone.size() - MAX_RETAINED_CHUNKS;
        for (ChunkPos pos : evictCandidates) {
            if (toRemove <= 0) break;
            if (st.cameraZone.remove(pos)) {
                lv.getChunkSource().removeRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(p.getUUID(), pos));
                toRemove--;
            }
        }
    }

    /** 重建待加队列：窗口内还没加票的区块，按前向半圆优先 + 距离近优先排序 */
    private void rebuildPending(PlayerState st) {
        st.pendingCameraChunks.clear();
        for (ChunkPos pos : st.cameraWindow) {
            if (!st.cameraZone.contains(pos)) {
                st.pendingCameraChunks.add(pos);
            }
        }
        sortPending(st);
    }

    private void sortPending(PlayerState st) {
        if (st.center == null || st.pendingCameraChunks.isEmpty()) return;
        double fx = -Math.sin(Math.toRadians(st.cameraYaw));
        double fz = Math.cos(Math.toRadians(st.cameraYaw));
        int cx = (st.center.x << 4) + 8;
        int cz = (st.center.z << 4) + 8;
        st.pendingCameraChunks.sort((a, b) -> {
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

    /** 每 tick 按预算从待加队列加票（前向/近处先加） */
    private void tickCameraZoneTickets(PlayerState st) {
        if (st.pendingCameraChunks.isEmpty()) return;
        if (!(st.player.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) st.player.level();
        int budget = Math.max(1, Config.preloadMaxRequestsPerTick);
        java.util.Iterator<ChunkPos> it = st.pendingCameraChunks.iterator();
        int added = 0;
        while (it.hasNext() && added < budget) {
            ChunkPos pos = it.next();
            it.remove();
            if (st.cameraZone.contains(pos)) continue;
            lv.getChunkSource().addRegionTicket(TICKET, pos, st.cameraTicketDistance, cameraTicketKey(st.player.getUUID(), pos));
            st.cameraZone.add(pos);
            added++;
        }
    }

    /** 每个玩家、每个区块一个独立 ticket key，避免多人播放互相撤票 */
    private static String cameraTicketKey(UUID player, ChunkPos pos) {
        return player + "|" + pos.x + "," + pos.z;
    }

    // ===== C 客户端缓存中心 =====

    private void sendCenter(ServerPlayer p, ChunkPos c) {
        p.connection.send(new ClientboundSetChunkCacheCenterPacket(c.x, c.z));
    }

    private static boolean isFar(ChunkPos cam, ChunkPos player, int viewDistance) {
        int t = Math.max(2, viewDistance);
        return Math.abs(cam.x - player.x) > t || Math.abs(cam.z - player.z) > t;
    }

    /** 玩家实际视距（区块数）：客户端渲染距离与服务端视距取小；未知时回退服务端视距 */
    private int viewDistanceChunks(PlayerState st) {
        if (st == null || st.player == null) return 2;
        int serverRd = Math.max(2, st.player.server.getPlayerList().getViewDistance());
        int playerRd = st.playerRenderDistance > 0 ? Math.max(2, st.playerRenderDistance) : serverRd;
        return Math.min(playerRd, serverRd);
    }

    private static String fmt(ChunkPos c) {
        return "[" + c.x + ", " + c.z + "]";
    }

    // ===== 释放 / 清理 =====

    private void release(UUID uuid, ServerPlayer p) {
        PlayerState st = states.remove(uuid);
        CameraAnchorManager.INSTANCE.removeAnchor(uuid);
        CameraEntitySyncManager.INSTANCE.removeAnchor(uuid);
        if (st == null) return;
        if (st.farMode) {
            setCameraZone(p, st, false);
            clearCameraArea(p, st);
            if (st.playerChunk != null) {
                sendCenter(p, st.playerChunk);
                resyncPlayerArea(p, st);
            }
        }
        forgetCameraChunks(p, st);
        LOGGER.info("预加载释放: 玩家 {}", p.getName().getString());
    }

    /** 更新相机锚点 + 实体同步器（无假人方案） */
    private void updateAnchors(UUID player, net.minecraft.server.level.ServerLevel level, ChunkPos cam, PlayerState st) {
        CameraAnchorManager.INSTANCE.setAnchor(player, level, cam,
                st.cameraMobRadius, st.cameraMobSpawn, st.cameraMobAi);
        CameraEntitySyncManager.INSTANCE.setAnchor(player, level, cam, st.cameraMobRadius);
    }

    /** 单个玩家的预加载状态 */
    private static final class PlayerState {
        ServerPlayer player;
        String scriptId = "";
        boolean farMode = false;
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
        final Set<ChunkPos> cameraWindow = new HashSet<>();
        final Set<ChunkPos> cameraZone = new HashSet<>();
        final List<ChunkPos> pendingCameraChunks = new ArrayList<>();
        final Set<ChunkPos> sentCameraChunks = new HashSet<>();
    }
}
