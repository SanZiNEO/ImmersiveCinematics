package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPreloadResultPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 区块预加载（服务端）— v5：远端镜头复用"玩家的加载逻辑"。
 * <ul>
 *   <li><b>门控</b>：仅「相机距玩家 &gt; farViewCenterThreshold」进入 far-view；近程零介入</li>
 *   <li><b>远端相机区</b>：直接给相机块挂半径 = 玩家视距({@code PlayerList.getViewDistance()}) 的区域 ticket，
 *       整块 (2V+1)² 按玩家设置加载——镜头朝哪都不缺（不再用 ±2 小窗口）</li>
 *   <li><b>补发</b>：服务端加载不等于发到客户端（玩家的 ChunkMap 只发玩家周围），相机区块由我们补发——
 *       维护"已发集合"，每 tick 限速（{@link Config#preloadMaxBurstPerTick}）把已 FULL 未发的区块包发过去</li>
 *   <li><b>B 玩家小块票券</b>：far-view 时对玩家块挂小块 ticket → 玩家区最小稳定加载、不卸载</li>
 *   <li><b>C 客户端中心</b>：far-view 时发 {@code ClientboundSetChunkCacheCenterPacket(相机块)}；返回/结束发回玩家块</li>
 * </ul>
 * 全程走 MC 公开 API，不反射、不 try/catch 兜底（有错即冒）。scanChunk 分流 + meta.preload 为后续细化。
 */
public final class ChunkPreloadManager {

    public static final ChunkPreloadManager INSTANCE = new ChunkPreloadManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ChunkPreload");

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("immersive_cinematics_camera", Comparator.comparingLong(ChunkPos::toLong));

    private final Map<UUID, PlayerState> states = new HashMap<>();
    private long lastStatusLog = 0;

    private ChunkPreloadManager() {}

    public void handleRequest(ServerPlayer player, int mode, String scriptId, int x, int z, int radius) {
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
        boolean freshScript = !scriptId.equals(st.scriptId);
        st.scriptId = scriptId;
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        st.playerChunk = new ChunkPos(player.blockPosition());
        if (isFar(cam, st.playerChunk) && freshScript) {
            enterFar(player, st, cam);
        }
        // 近程 / 同脚本重入：零介入
    }

    /** 相机位置上报：far-view 时区域 ticket 跟随相机；越过/回到阈值切换 */
    public void handlePosition(ServerPlayer player, int x, int z) {
        PlayerState st = states.get(player.getUUID());
        if (st == null) return;
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        boolean farNow = isFar(cam, st.playerChunk);
        if (farNow && !st.farMode) {
            enterFar(player, st, cam);
            return;
        }
        if (!farNow && st.farMode) {
            // 回到玩家视距：撤区域票、清已发、还玩家小块、中心→玩家
            st.farMode = false;
            removeRegionTicket(player, st.center, st.regionRadius);
            st.sent.clear();
            st.center = null;
            LOGGER.info("[preload far-end] 玩家={} 中心→玩家块 {}", player.getName().getString(), fmt(st.playerChunk));
            setPlayerZone(player, st, false);
            sendCenter(player, st.playerChunk);
            return;
        }
        if (farNow && st.farMode && !cam.equals(st.center)) {
            // 相机跨块：区域 ticket 搬移 + 中心跟随 + 清理已离区的记账
            moveRegionTicket(player, st, cam);
            sendCenter(player, cam);
            pruneSent(st, cam);
        }
    }

    /** 由 ServerEventHandler 服务端 tick 调用 */
    public void tick() {
        long now = System.currentTimeMillis();
        int maxBurst = Math.max(1, Config.preloadMaxBurstPerTick);
        int sentThisTickTotal = 0;
        for (PlayerState st : states.values()) {
            if (st.player == null || !st.farMode || st.center == null) continue;
            sentThisTickTotal += pollOnce(st, now, maxBurst);
        }
        if (now - lastStatusLog >= 1000 && !states.isEmpty()) {
            lastStatusLog = now;
            for (PlayerState st : states.values()) {
                if (st.player == null) continue;
                LOGGER.info("[preload status] 玩家={} far={} 中心={} 玩家块={} 区域半径={} 已发={} 玩家小块={}",
                        st.player.getName().getString(),
                        st.farMode,
                        st.center != null ? fmt(st.center) : "null",
                        st.playerChunk != null ? fmt(st.playerChunk) : "null",
                        st.regionRadius, st.sent.size(), st.playerZone.size());
            }
        }
        if (sentThisTickTotal > 0) {
            LOGGER.info("[preload 本tick补发] {}", sentThisTickTotal);
        }
    }

    /** 玩家断线清理 */
    public void onDisconnect(UUID uuid, ServerPlayer player) {
        release(uuid, player);
    }

    // ===== far-view 生命周期 =====

    private void enterFar(ServerPlayer player, PlayerState st, ChunkPos cam) {
        st.playerChunk = new ChunkPos(player.blockPosition());
        st.farMode = true;
        st.center = cam;
        MinecraftServer server = player.server;
        st.regionRadius = Math.max(2, server.getPlayerList().getViewDistance());
        addRegionTicket(player, cam, st.regionRadius);
        LOGGER.info("[preload far-start] 玩家={} 中心→{} 玩家块={} 区域半径={}（按玩家视距）阈值={}",
                player.getName().getString(), fmt(cam), fmt(st.playerChunk),
                st.regionRadius, Config.preloadFarViewCenterThreshold);
        setPlayerZone(player, st, true);
        sendCenter(player, cam);
    }

    private void moveRegionTicket(ServerPlayer p, PlayerState st, ChunkPos newCenter) {
        removeRegionTicket(p, st.center, st.regionRadius);
        st.center = newCenter;
        addRegionTicket(p, newCenter, st.regionRadius);
    }

    /** 每 tick 补发：相机±区域半径内已 FULL 未发的区块，限速发包 */
    private int pollOnce(PlayerState st, long now, int maxBurst) {
        ServerPlayer p = st.player;
        if (!(p.level() instanceof net.minecraft.server.level.ServerLevel)) return 0;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
        int budget = maxBurst;
        int r = st.regionRadius;
        ChunkPos c = st.center;
        outer:
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (budget <= 0) break outer;
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                if (st.sent.containsKey(pos)) continue;
                if (!level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                ClientboundLevelChunkWithLightPacket packet =
                        new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
                p.connection.send(packet); // 先发后记账：send 失败即冒错，不会误记已发
                st.sent.put(pos, now);
                budget--;
            }
        }
        return maxBurst - budget;
    }

    /** 清理已离开相机区域的已发记账，防止 long-fly 时 sent 无限增长 */
    private void pruneSent(PlayerState st, ChunkPos cam) {
        int margin = st.regionRadius + 2;
        st.sent.keySet().removeIf(pos ->
                Math.abs(pos.x - cam.x) > margin || Math.abs(pos.z - cam.z) > margin);
    }

    private void addRegionTicket(ServerPlayer p, ChunkPos center, int radius) {
        if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
            level.getChunkSource().addRegionTicket(TICKET, center, radius, center);
        }
    }

    private void removeRegionTicket(ServerPlayer p, ChunkPos center, int radius) {
        if (center == null) return;
        if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
            level.getChunkSource().removeRegionTicket(TICKET, center, radius, center);
        }
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
                            net.minecraft.server.level.ServerLevel lv =
                                    (net.minecraft.server.level.ServerLevel) p.level();
                            lv.getChunkSource().addRegionTicket(TICKET, pos, 1, pos);
                        }
                    }
                }
            }
        } else if (!on && !st.playerZone.isEmpty()) {
            for (ChunkPos pos : new java.util.HashSet<>(st.playerZone)) {
                if (p.level() instanceof net.minecraft.server.level.ServerLevel) {
                    net.minecraft.server.level.ServerLevel lv =
                            (net.minecraft.server.level.ServerLevel) p.level();
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
        if (st == null) return;
        if (st.farMode || st.center != null) {
            removeRegionTicket(p, st.center, st.regionRadius);
            if (st.farMode && st.playerChunk != null) sendCenter(p, st.playerChunk);
        }
        setPlayerZone(p, st, false);
        st.sent.clear();
        LOGGER.info("预加载释放: 玩家 {}", p.getName().getString());
    }

    /** 单个玩家的预加载状态 */
    private static final class PlayerState {
        ServerPlayer player;
        String scriptId = "";
        boolean farMode = false;
        ChunkPos center;
        ChunkPos playerChunk;
        int regionRadius = 2;
        final Map<ChunkPos, Long> sent = new HashMap<>();
        final Set<ChunkPos> playerZone = new HashSet<>();
    }
}
