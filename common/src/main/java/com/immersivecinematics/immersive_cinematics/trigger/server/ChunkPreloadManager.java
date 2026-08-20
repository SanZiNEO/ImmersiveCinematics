package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPreloadResultPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
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

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("immersive_cinematics_camera", Comparator.comparingLong(ChunkPos::toLong));

    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Map<net.minecraft.server.level.ServerLevel, ChunkTicketPool> pools = new HashMap<>();
    /** 磁盘扫描缓存：pos→已生成(true)/未生成(false)；会话内磁盘状态稳定 */
    private final Map<Long, Boolean> scanCache = new HashMap<>();
    private final Set<Long> scanInFlight = new HashSet<>();
    private long lastStatusLog = 0;

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
            // lookahead 预载：只加票、不发包、慢速预算；跳过去后由 far-view 接管
            st.playerChunk = new ChunkPos(player.blockPosition());
            st.cameraYaw = yaw;
            st.playerRenderDistance = renderDistance;
            if (st.prewarm != null) removePrewarmTickets(player, st);
            st.prewarm = new PrewarmState();
            st.prewarm.center = new ChunkPos(x >> 4, z >> 4);
            st.prewarm.radius = Math.max(1, radius);
            st.prewarm.ticketed.clear();
            LOGGER.info("[preload prewarm-start] 玩家={} 目标={} 半径={}",
                    player.getName().getString(), fmt(st.prewarm.center), st.prewarm.radius);
            return;
        }
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.playerChunk = new ChunkPos(player.blockPosition());
        st.cameraYaw = yaw;
        st.playerRenderDistance = renderDistance;
        if (isFar(cam, st.playerChunk) && freshScript) {
            enterFar(player, st, cam); // 立即进入（无防抖；退出/回玩家靠玩家区对账补发兜底）
        }
        // 近程 / 同脚本重入：零介入
    }

    /** 相机位置上报：far 立即进入；far-view 时差集滑动；回到玩家视距 → 释放 + 玩家区补发 */
    public void handlePosition(ServerPlayer player, int x, int z, float yaw) {
        PlayerState st = states.get(player.getUUID());
        if (st == null) return;
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.cameraYaw = yaw;
        boolean farNow = isFar(cam, st.playerChunk);
        if (farNow && !st.farMode) {
            enterFar(player, st, cam);
            return;
        }
        if (farNow && st.farMode && !cam.equals(st.center)) {
            // far 滑到预载目标：把预载所有权转移给 far（票不撤、count 不变）
            if (st.prewarm != null && st.prewarm.center != null && st.prewarm.center.equals(cam)) {
                st.ticketed.addAll(st.prewarm.ticketed);
                st.prewarm = null;
            }
            st.center = cam;
            if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                CameraMobManager.INSTANCE.setAnchor(player.getUUID(), serverLevel, cam,
                        st.cameraMobRadius, st.cameraMobSpawn, st.cameraMobAi);
            }
            updateDesired(st, cam);
            sendCenter(player, cam);
            pruneSent(st, cam);
            return;
        }
        if (!farNow && st.farMode) {
            // 回到玩家视距：撤票、还玩家小块、中心回玩家、玩家区对账补发
            st.farMode = false;
            clearCameraArea(player, st);
            setPlayerZone(player, st, false);
            LOGGER.info("[preload far-end] 玩家={} 中心→玩家块 {}", player.getName().getString(), fmt(st.playerChunk));
            sendCenter(player, st.playerChunk);
            resyncPlayerArea(player, st);
            return;
        }
        if (!farNow) {
            return;
        }
    }

    /** 由 ServerEventHandler 服务端 tick 调用 */
    public void tick() {
        long now = System.currentTimeMillis();
        int requestBudget = Math.max(1, Config.preloadMaxRequestsPerTick);
        int sendBudget = Math.max(1, Config.preloadMaxBurstPerTick);
        for (PlayerState st : states.values()) {
            if (st.player == null || !st.farMode || st.center == null) continue;
            requestTickets(st, requestBudget);
            sendReady(st, sendBudget);
        }
        // lookahead 预载：独立慢速加票（只加票、不发包）
        for (PlayerState st : states.values()) {
            if (st.player == null || st.prewarm == null) continue;
            requestPrewarmTickets(st, Math.max(1, Config.preloadPrewarmRequestsPerTick));
        }
        if (now - lastStatusLog >= 1000 && !states.isEmpty()) {
            lastStatusLog = now;
            for (PlayerState st : states.values()) {
                if (st.player == null) continue;
                int playerEntities = 0;
                int cameraEntities = 0;
                if (st.player.level() instanceof ServerLevel serverLevel) {
                    if (st.playerChunk != null) {
                        playerEntities = countEntities(serverLevel, st.playerChunk, st.regionRadius);
                    }
                    if (st.center != null) {
                        cameraEntities = countEntities(serverLevel, st.center, st.regionRadius);
                    }
                }
                LOGGER.info("[preload status] 玩家={} far={} 中心={} 玩家块={} 半径={} 目标={} 已票={} 已发={} 玩家小块={} 玩家区已载={} 预载票={} 玩家实体={} 相机实体={}",
                        st.player.getName().getString(),
                        st.farMode,
                        st.center != null ? fmt(st.center) : "null",
                        st.playerChunk != null ? fmt(st.playerChunk) : "null",
                        st.regionRadius, st.desired.size(), st.ticketed.size(), st.sent.size(),
                        st.playerZone.size(),
                        countLoadedPlayerArea(st),
                        st.prewarm != null ? st.prewarm.ticketed.size() : 0,
                        playerEntities, cameraEntities);
            }
        }
    }

    /** 玩家断线清理 */
    public void onDisconnect(UUID uuid, ServerPlayer player) {
        release(uuid, player);
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
            CameraMobManager.INSTANCE.setAnchor(player.getUUID(), serverLevel, cam,
                    st.cameraMobRadius, st.cameraMobSpawn, st.cameraMobAi);
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
        // 预载目标无论是否等于 far 中心，都先释放 prewarm 的真实 ticket；
        // far 区域改由隐藏假人驱动加载，不需要把 prewarm 的 ticket 合并过来。
        if (st.prewarm != null) {
            removePrewarmTickets(player, st);
            st.prewarm = null;
        }
        LOGGER.info("[preload far-start] 玩家={} 中心→{} 玩家块={} 区域半径={}（玩家={}/服务端={}/cap={}/强制={}）阈值={}",
                player.getName().getString(), fmt(cam), fmt(st.playerChunk),
                st.regionRadius, st.playerRenderDistance, serverRd, cap,
                Config.preloadForceRadius ? Config.preloadForceRadiusValue : "-",
                Config.preloadFarViewCenterThreshold);
        updateDesired(st, cam);
        setPlayerZone(player, st, true);
        sendCenter(player, cam);
    }

    /** desired = 相机 ± regionRadius 全圆（最终全加载）；处理顺序见 sortedByPriority（先可见、后补全） */
    private void updateDesired(PlayerState st, ChunkPos cam) {
        st.center = cam;
        Set<ChunkPos> next = new HashSet<>();
        for (int dx = -st.regionRadius; dx <= st.regionRadius; dx++) {
            for (int dz = -st.regionRadius; dz <= st.regionRadius; dz++) {
                next.add(new ChunkPos(cam.x + dx, cam.z + dz));
            }
        }
        st.desired.removeIf(pos -> {
            if (!next.contains(pos)) {
                st.ticketed.remove(pos);
                return true;
            }
            return false;
        });
        st.desired.addAll(next);
    }

    private void requestTickets(PlayerState st, int budget) {
        // 相机区域由隐藏假人（CameraFakePlayer）驱动原版加载/生成，这里只负责“标记待发送给真实客户端”。
        ServerPlayer p = st.player;
        if (p == null) return;
        int added = 0;
        for (ChunkPos pos : sortedByPriority(st)) {
            if (added >= budget) break;
            if (st.ticketed.contains(pos)) continue;
            st.ticketed.add(pos);
            added++;
        }
    }

    /** 只读磁盘 Status 分流：已生成("minecraft:full") vs 未生成；结果缓存，回调回主线程写缓存 */
    private void classify(net.minecraft.server.level.ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        if (scanCache.containsKey(key) || scanInFlight.contains(key)) return;
        scanInFlight.add(key);
        CollectFields fields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
        level.getChunkSource().chunkScanner().scanChunk(pos, fields).thenAccept(v -> {
            String status = ((CompoundTag) fields.getResult()).getString("Status");
            boolean generated = "minecraft:full".equals(status);
            level.getServer().execute(() -> {
                scanCache.put(key, generated);
                scanInFlight.remove(key);
            });
        });
    }

    private void sendReady(PlayerState st, int budget) {
        ServerPlayer p = st.player;
        if (!(p.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
        int sent = 0;
        for (ChunkPos pos : sortedByPriority(st)) {
            if (sent >= budget) break;
            if (!st.ticketed.contains(pos) || st.sent.containsKey(pos)) continue;
            if (!level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)); // 先发后记账
            sendEntitiesForChunk(level, pos, p);
            st.sent.put(pos, System.currentTimeMillis());
            sent++;
        }
    }

    /** 相机可见性：前方半平面（dot >= 0） */
    private boolean isVisible(PlayerState st, ChunkPos pos) {
        double rad = Math.toRadians(st.cameraYaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        int dx = pos.x - st.center.x;
        int dz = pos.z - st.center.z;
        return dx * fx + dz * fz >= 0;
    }

    /** 优先级：先可见（P1）后不可见（P2），同组内按距相机近→远（先加载能看到的，再补剩下的） */
    private List<ChunkPos> sortedByPriority(PlayerState st) {
        List<ChunkPos> list = new ArrayList<>(st.desired);
        ChunkPos c = st.center;
        list.sort(Comparator
                .comparingInt((ChunkPos pos) -> isVisible(st, pos) ? 0 : 1)
                .thenComparingLong(pos -> {
                    long dx = pos.x - c.x;
                    long dz = pos.z - c.z;
                    return dx * dx + dz * dz;
                }));
        return list;
    }

    /** lookahead 预载：给预载目标区域慢速加票（只加票、不发包；跳过去后 far-view 接管补发） */
    private void requestPrewarmTickets(PlayerState st, int budget) {
        ServerPlayer p = st.player;
        if (p == null || st.prewarm == null) return;
        ChunkPos c = st.prewarm.center;
        int r = st.prewarm.radius;
        int added = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (added >= budget) return;
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                if (st.prewarm.ticketed.contains(pos)) continue;
                if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
                    net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
                    pool(level).request(level, pos, st.cameraTicketDistance);
                }
                st.prewarm.ticketed.add(pos);
                added++;
            }
        }
    }

    private void removePrewarmTickets(ServerPlayer p, PlayerState st) {
        if (st.prewarm == null) return;
        for (ChunkPos pos : new HashSet<>(st.prewarm.ticketed)) {
            removeTicket(p, pos, st.cameraTicketDistance);
        }
        st.prewarm.ticketed.clear();
    }

    private void pruneSent(PlayerState st, ChunkPos cam) {
        int margin = st.regionRadius + 2;
        st.sent.keySet().removeIf(pos ->
                Math.abs(pos.x - cam.x) > margin || Math.abs(pos.z - cam.z) > margin);
    }

    private void removeTicket(ServerPlayer p, ChunkPos pos, int distance) {
        if (p == null) return;
        if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
            pool(level).release(level, pos, distance);
        }
    }

    private void clearCameraArea(ServerPlayer p, PlayerState st) {
        st.ticketed.clear();
        st.desired.clear();
        st.sent.clear();
        st.center = null;
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

    /** 区块发给真实玩家后，补发该区块内尚未同步过的实体（去重逻辑在 CameraMobManager） */
    private void sendEntitiesForChunk(ServerLevel level, ChunkPos pos, ServerPlayer player) {
        CameraMobManager.INSTANCE.sendEntitiesForChunk(player, level, pos);
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

    // ===== B 玩家小块票券（far-view 防卸载；直挂票券、不发包） =====

    private void setPlayerZone(ServerPlayer p, PlayerState st, boolean on) {
        if (on && st.playerZone.isEmpty()) {
            int r = Math.max(1, Config.preloadPlayerZoneRadius);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    ChunkPos pos = new ChunkPos(st.playerChunk.x + dx, st.playerChunk.z + dz);
                    if (st.playerZone.add(pos)) {
                        if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
                            net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) p.level();
                            lv.getChunkSource().addRegionTicket(TICKET, pos, 1, pos);
                        }
                    }
                }
            }
        } else if (!on && !st.playerZone.isEmpty()) {
            for (ChunkPos pos : new HashSet<>(st.playerZone)) {
                if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
                    net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) p.level();
                    lv.getChunkSource().removeRegionTicket(TICKET, pos, 1, pos);
                }
            }
            st.playerZone.clear();
        }
    }

    // ===== C 客户端缓存中心 =====

    private void sendCenter(ServerPlayer p, ChunkPos c) {
        p.connection.send(new ClientboundSetChunkCacheCenterPacket(c.x, c.z));
    }

    private static boolean isFar(ChunkPos cam, ChunkPos player) {
        int t = Math.max(2, Config.preloadFarViewCenterThreshold);
        return Math.abs(cam.x - player.x) > t || Math.abs(cam.z - player.z) > t;
    }

    private static String fmt(ChunkPos c) {
        return "[" + c.x + ", " + c.z + "]";
    }

    // ===== 释放 / 清理 =====

    private void release(UUID uuid, ServerPlayer p) {
        PlayerState st = states.remove(uuid);
        CameraMobManager.INSTANCE.removeAnchor(uuid);
        if (st == null) return;
        if (st.farMode) {
            clearCameraArea(p, st);
            setPlayerZone(p, st, false);
            if (st.playerChunk != null) {
                sendCenter(p, st.playerChunk);
                resyncPlayerArea(p, st);
            }
        }
        removePrewarmTickets(p, st);
        st.prewarm = null;
        LOGGER.info("预加载释放: 玩家 {}", p.getName().getString());
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
        int worldgenIssued = 0;
        final Set<ChunkPos> desired = new HashSet<>();
        final Set<ChunkPos> ticketed = new HashSet<>();
        final Map<ChunkPos, Long> sent = new HashMap<>();
        final Set<ChunkPos> playerZone = new HashSet<>();
        PrewarmState prewarm;
    }

    /** lookahead 预载状态（只加票、不发包） */
    private static final class PrewarmState {
        ChunkPos center;
        int radius = Config.preloadPrewarmRadius;
        final Set<ChunkPos> ticketed = new HashSet<>();
    }
}
