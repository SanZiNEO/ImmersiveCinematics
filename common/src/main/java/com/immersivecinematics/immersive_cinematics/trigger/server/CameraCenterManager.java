package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraCenterRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 相机中心同步（服务端）— 虚拟相机中心方案：把玩家的虚拟 Section 写入 {@code ChunkMapCameraMixin}
 * 再调原版 {@code ChunkMap.move}，让 DistanceManager 的正方形 ticket 加载与 ChunkMap 的圆角
 * isChunkInRange 发送/遗忘自动围绕相机中心运转；清除虚拟中心即交还原版玩家差集。不自建差集、不手动发包。
 *
 * 状态以客户端生成的 {@code sessionId} 定代：START 替换会话，位置包只接受当前会话，END 只释放匹配会话，
 * 旧播放的迟到包不会误杀新播放。所有入口都在主线程。
 */
public final class CameraCenterManager {

    public static final CameraCenterManager INSTANCE = new CameraCenterManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraCenter");

    private final Map<UUID, PlayerState> states = new HashMap<>();

    private CameraCenterManager() {}

    /**
     * 客户端相机中心请求。加载半径与发送范围全交原版 ChunkMap，本方法只写入中心与锚点。
     */
    public void handleRequest(ServerPlayer player, int mode, String scriptId, long sessionId,
                              double x, double y, double z, boolean cameraMode) {
        UUID uuid = player.getUUID();
        if (Config.debugLogging) {
            LOGGER.info("[camera center req] 玩家={} mode={} script={} session={} 中心=({},{},{}) 玩家块=({},{}) cameraMode={}",
                    player.getName().getString(), mode, scriptId, sessionId, x, y, z,
                    player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4, cameraMode);
        }
        if (mode == C2SCameraCenterRequestPacket.MODE_END) {
            PlayerState st = states.get(uuid);
            if (st != null && st.sessionId == sessionId) {
                release(uuid, player, "stop");
            } else if (st != null && Config.debugLogging) {
                LOGGER.info("[camera center] 忽略过期 END 玩家={} session={} current={}",
                        player.getName().getString(), sessionId, st.sessionId);
            }
            return;
        }
        if (mode != C2SCameraCenterRequestPacket.MODE_START) return;

        CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
        if (script != null && !hasCameraTrack(script)) {
            return;
        }

        PlayerState st = states.computeIfAbsent(uuid, k -> new PlayerState());
        st.player = player;
        st.scriptId = scriptId;
        st.sessionId = sessionId;
        // 新播放开始：旧中心作废，首报必须让原版做一次 old/new 差集
        st.lastAppliedCenter = null;
        st.lastReportTick = tickCount(player);
        // 诊断会话必须先于首次 ChunkMap.move 建立：move 会把相机区实体批量 addPairing，那些 add 才是
        // pairSeen 的主要来源；会话内的 paired 按实体 id 去重，旧位置的 removePairing 因 id 不在集合中
        // 天然被忽略，不会反向污染计数（因此无需像「move 之后再建会话」那样丢掉首次批量配对）。
        CameraCenterDiagnostics.onStart(player, scriptId, sessionId);
        applyVirtualCamera(player, st, x, y, z, cameraMode);
    }

    /** 只有非空 CAMERA 轨道才允许同步中心；纯 OVERLAY/EVENT 脚本由服务端兜底拒绝 */
    private static boolean hasCameraTrack(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        return track.isPresent() && !track.get().getClips().isEmpty();
    }

    /**
     * 相机中心位置上报：更新虚拟相机中心，交给原版 ChunkMap.move 做差集。
     * 只接受当前会话；会话不匹配（旧播放的迟到包）直接丢弃。
     */
    public void handlePosition(ServerPlayer player, long sessionId, double x, double y, double z, boolean cameraMode) {
        PlayerState st = states.get(player.getUUID());
        if (st == null || st.sessionId != sessionId) return;
        st.lastReportTick = tickCount(player);
        if (Config.debugLogging) {
            LOGGER.info("[camera center pos] 玩家={} session={} 中心=({},{},{}) cameraMode={}",
                    player.getName().getString(), sessionId, x, y, z, cameraMode);
        }
        applyVirtualCamera(player, st, x, y, z, cameraMode);
    }

    /** 玩家断线清理（强制释放：连接已断，不再等 END） */
    public void onDisconnect(UUID uuid, ServerPlayer player) {
        release(uuid, player, "disconnect");
    }

    /** 客户端上报脚本结束 → 只在脚本匹配时释放，避免旧脚本的结束包杀掉新播放的中心 */
    public void onScriptFinished(ServerPlayer player, String scriptId) {
        PlayerState st = states.get(player.getUUID());
        if (st == null) return;
        if (!scriptId.isEmpty() && !scriptId.equals(st.scriptId)) {
            if (Config.debugLogging) {
                LOGGER.info("[camera center] 忽略脚本结束 玩家={} script={} current={}",
                        player.getName().getString(), scriptId, st.scriptId);
            }
            return;
        }
        release(player.getUUID(), player, "stop");
    }

    /** 包处理异常兜底：强制释放半更新的虚拟相机中心 / 锚点 / 玩家状态，避免坏状态留在服务端。 */
    public void releaseOnFailure(ServerPlayer player) {
        release(player.getUUID(), player, "failure");
    }

    // ===== 超时 =====

    /**
     * 每 tick 兜底：玩家已移除，或客户端超过 {@code max(100, 5 * interval)} tick 没有上报 → 释放。
     * 客户端停发（崩溃 / 断线 / END 丢失）时不会让旧相机中心永久留在服务端。
     */
    public void tick(MinecraftServer server) {
        CameraCenterDiagnostics.onServerTick(server);
        if (states.isEmpty()) return;
        long now = server.getTickCount();
        long timeout = Math.max(100L, 5L * Config.cameraCenterReportInterval);
        for (Map.Entry<UUID, PlayerState> entry : List.copyOf(states.entrySet())) {
            UUID uuid = entry.getKey();
            PlayerState st = entry.getValue();
            ServerPlayer player = st.player;
            long age = now - st.lastReportTick;
            if (player == null || player.isRemoved()) {
                CameraCenterDiagnostics.onTimeout(uuid, st.scriptId, st.sessionId, age, timeout);
                release(uuid, player, "timeout");
                continue;
            }
            if (age > timeout) {
                CameraCenterDiagnostics.onTimeout(uuid, st.scriptId, st.sessionId, age, timeout);
                release(uuid, player, "timeout");
                continue;
            }
            CameraCenterDiagnostics.onState(server, player, st.moves,
                    st.moves > 0 ? now - st.lastMoveTick : -1L);
        }
    }

    // ===== 释放 =====

    private void release(UUID uuid, ServerPlayer player, String reason) {
        PlayerState st = states.remove(uuid);
        CameraAnchorManager.INSTANCE.removeAnchor(uuid);
        CameraCenterDiagnostics.onRelease(uuid,
                st != null ? st.scriptId : "", st != null ? st.sessionId : 0L,
                reason, states.size(), st != null);
        if (player != null) {
            clearVirtualCamera(player);
        } else {
            // 玩家对象已不可用（超时 / 卸载）：必须无条件清表，不能再依赖 move
            CameraVirtualCenterState.clearCamera(uuid);
        }
    }

    /** 服务端停止 / 换档：只清三张表与诊断会话，不做 move（chunk source 可能已关闭）。 */
    public void clearAll() {
        states.clear();
        CameraAnchorManager.INSTANCE.clearAll();
        CameraVirtualCenterState.clearAll();
        CameraCenterDiagnostics.clearAll();
    }

    /** 维度卸载：移除该维度的状态 / 锚点 / 虚拟中心，不触发 move；逐条输出 level-unload 释放日志。 */
    public void onLevelUnload(ServerLevel level) {
        List<UUID> removedIds = new ArrayList<>();
        List<PlayerState> removedStates = new ArrayList<>();
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            PlayerState st = entry.getValue();
            if (st.level == level || (st.player != null && st.player.level() == level)) {
                removedIds.add(entry.getKey());
                removedStates.add(st);
            }
        }
        for (UUID uuid : removedIds) {
            states.remove(uuid);
        }
        for (int i = 0; i < removedIds.size(); i++) {
            PlayerState st = removedStates.get(i);
            CameraCenterDiagnostics.onRelease(removedIds.get(i), st.scriptId, st.sessionId,
                    "level-unload", states.size(), true);
        }
        CameraAnchorManager.INSTANCE.removeForLevel(level);
        CameraVirtualCenterState.clearLevel(level);
    }

    // ===== 虚拟相机中心 =====

    /**
     * 写入虚拟相机中心（每次上报都写），再按阈值决定是否让原版 {@code ChunkMap.move} 刷新差集 / 配对。
     * 刷新条件：维度变化、cameraMode 翻转、中心区块变化，或位移 ≥ 4 格且距上次 move ≥ 2 tick。
     * {@code cameraMode == false} 时同时清虚拟中心与锚点（不留玩家位置锚点）。
     */
    private void applyVirtualCamera(ServerPlayer player, PlayerState st, double x, double y, double z, boolean cameraMode) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        ServerChunkCache cache = serverLevel.getChunkSource();
        Vec3 center = new Vec3(x, y, z);

        boolean wasCameraMode = CameraVirtualCenterState.cameraCenterFor(player) != null;
        if (cameraMode) {
            CameraVirtualCenterState.setCamera(player.getUUID(), serverLevel, x, y, z);
            CameraAnchorManager.INSTANCE.setAnchor(player.getUUID(), serverLevel, center);
        } else {
            CameraVirtualCenterState.clearCamera(player.getUUID());
            CameraAnchorManager.INSTANCE.removeAnchor(player.getUUID());
        }

        long now = serverLevel.getServer().getTickCount();
        boolean levelChanged = st.level != serverLevel;
        boolean flipped = wasCameraMode != cameraMode;
        boolean sectionChanged = st.lastAppliedCenter == null
                || chunkX(st.lastAppliedCenter.x) != chunkX(x)
                || chunkX(st.lastAppliedCenter.z) != chunkX(z);
        double movedSq = st.lastAppliedCenter == null ? Double.MAX_VALUE : center.distanceToSqr(st.lastAppliedCenter);
        st.level = serverLevel;
        boolean shouldMove = levelChanged || flipped || sectionChanged
                || (movedSq >= 16.0 && now - st.lastMoveTick >= 2);
        if (!shouldMove) return;

        st.lastAppliedCenter = center;
        st.lastMoveTick = now;
        st.moves++;
        String reason = levelChanged ? "level" : flipped ? "flip" : sectionChanged ? "section" : "threshold";
        CameraCenterDiagnostics.onMove(player, reason, st.moves, center);
        if (!player.isRemoved()) {
            cache.move(player);
        }
    }

    /** 清除虚拟相机中心，并让原版从相机中心切回玩家真实位置；玩家已移除时只清表。 */
    private void clearVirtualCamera(ServerPlayer player) {
        CameraVirtualCenterState.clearCamera(player.getUUID());
        if (player.isRemoved()) return;
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().move(player);
        }
    }

    private static int chunkX(double coord) {
        return Mth.floor(coord) >> 4;
    }

    private static long tickCount(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null ? server.getTickCount() : 0L;
    }

    /** 单个玩家的相机中心同步状态 */
    private static final class PlayerState {
        ServerPlayer player;
        String scriptId = "";
        /** 客户端每次新 START 生成的会话 id；位置包 / END 只认当前会话 */
        long sessionId;
        /** 最近一次应用到的维度（维度变化强制 move） */
        ServerLevel level;
        /** 最近一次收到 START / POS 的 tick（超时兜底） */
        long lastReportTick;
        /** 最近一次真正应用到原版 ChunkMap 的中心（section 变化与位移阈值比较用） */
        Vec3 lastAppliedCenter;
        long lastMoveTick;
        int moves;
    }
}
