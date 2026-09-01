package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPreloadResultPacket;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 区块预加载（服务端）— 虚拟相机中心方案。
 * <p>
 * 不再自建差集/手动发包：相机激活/移动/结束时，把玩家的虚拟 Section 写入
 * {@code ChunkMapCameraMixin}，然后调用原版 {@code ChunkMap.move}，让原版的：
 * <ul>
 *   <li>DistanceManager 正方形 ticket 加载</li>
 *   <li>ChunkMap 圆角 isChunkInRange 客户端发送/遗忘</li>
 * </ul>
 * 自动围绕相机中心运转；玩家/空档时清除虚拟中心即可交还原版玩家差集。
 */
public final class ChunkPreloadManager {

    public static final ChunkPreloadManager INSTANCE = new ChunkPreloadManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ChunkPreload");

    private final Map<UUID, PlayerState> states = new HashMap<>();

    private ChunkPreloadManager() {}

    public void handleRequest(ServerPlayer player, int mode, String scriptId, int x, int z, int radius, float yaw, int renderDistance,
                              boolean cameraMode,
                              boolean cameraMobSpawn, int cameraMobRadius, boolean cameraMobAi) {
        if (!Config.preloadEnabled) {
            S2CPreloadResultPacket.send(player, scriptId, "预加载全局已关闭");
            return;
        }
        UUID uuid = player.getUUID();
        if (Config.debugLogging) {
            LOGGER.info("[preload req] 玩家={} mode={} script={} 相机=({},{}) 玩家块=({},{}) cameraMode={}",
                    player.getName().getString(), mode, scriptId, x >> 4, z >> 4,
                    player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4,
                    cameraMode);
        }
        if (mode == C2SPreloadRequestPacket.MODE_RELEASE) {
            release(uuid, player);
            return;
        }
        if (mode == C2SPreloadRequestPacket.MODE_PREWARM) {
            // 预热已废弃：原版虚拟相机中心会在片段激活时直接按原版规则加载。
            return;
        }
        CinematicScript preloadScript = ScriptManager.INSTANCE.getScript(scriptId);
        if (preloadScript != null && !hasCameraTrack(preloadScript)) {
            S2CPreloadResultPacket.send(player, scriptId, "脚本无CAMERA轨道，跳过预加载");
            return;
        }

        PlayerState st = states.computeIfAbsent(uuid, k -> new PlayerState());
        st.player = player;
        st.scriptId = scriptId;
        st.cameraMobSpawn = cameraMobSpawn;
        st.cameraMobRadius = Math.max(1, Math.min(16, cameraMobRadius));
        st.cameraMobAi = cameraMobAi;

        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        if (player.level() instanceof ServerLevel serverLevel) {
            updateAnchors(uuid, serverLevel, cam, st);
        }
        applyVirtualCamera(player, st, cam, cameraMode);
    }

    /** 只有非空 CAMERA 轨道才允许预加载；纯 OVERLAY/EVENT 脚本由服务端兜底拒绝 */
    private static boolean hasCameraTrack(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        return track.isPresent() && !track.get().getClips().isEmpty();
    }

    /**
     * 相机位置上报：更新虚拟相机中心，交给原版 ChunkMap.move 做差集。
     */
    public void handlePosition(ServerPlayer player, int x, int z, float yaw, boolean cameraMode) {
        PlayerState st = states.get(player.getUUID());
        if (st == null) return;
        ChunkPos cam = new ChunkPos(x >> 4, z >> 4);
        if (Config.debugLogging) {
            LOGGER.info("[preload pos] 玩家={} 相机=({},{}) cameraMode={}",
                    player.getName().getString(), cam.x, cam.z, cameraMode);
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            updateAnchors(player.getUUID(), serverLevel, cam, st);
        }
        applyVirtualCamera(player, st, cam, cameraMode);
    }

    /** 玩家断线清理 */
    public void onDisconnect(UUID uuid, ServerPlayer player) {
        release(uuid, player);
    }

    /** 客户端上报脚本结束 → 强制释放 */
    public void onScriptFinished(ServerPlayer player) {
        release(player.getUUID(), player);
    }

    // ===== 释放 =====

    private void release(UUID uuid, ServerPlayer p) {
        PlayerState st = states.remove(uuid);
        CameraAnchorManager.INSTANCE.removeAnchor(uuid);
        if (st == null) {
            // 即使没有状态，也确保虚拟相机中心被清理并让原版回到玩家真实位置
            clearVirtualCamera(p);
            return;
        }
        LOGGER.info("预加载释放: 玩家 {} 清除虚拟相机中心，交还原版玩家差集", p.getName().getString());
        clearVirtualCamera(p);
    }

    // ===== 虚拟相机中心 =====

    /** 设置/清除虚拟相机中心，并让原版 ChunkMap.move 执行一次 old/new 差集。 */
    private void applyVirtualCamera(ServerPlayer player, PlayerState st, ChunkPos cam, boolean cameraMode) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        ServerChunkCache cache = serverLevel.getChunkSource();
        ChunkMap chunkMap = cache.chunkMap;
        if (!(chunkMap instanceof CameraVirtualCenterAccess access)) return;

        // 状态没变就不重复设置/触发 move，避免每次 POS 都刷日志和做一次原版差集
        boolean sameState = st.virtualCenterActive == cameraMode
                && (!cameraMode || cam.equals(st.lastVirtualCenter));
        if (sameState) return;

        if (cameraMode) {
            access.immersiveCinematics$setCameraSection(player.getUUID(), SectionPos.of(cam, 0));
            if (Config.debugLogging) {
                LOGGER.info("[preload virtual] 玩家={} 设置虚拟相机中心 {}，切到相机差集", player.getName().getString(), fmt(cam));
            }
        } else {
            access.immersiveCinematics$clearCameraSection(player.getUUID());
            if (Config.debugLogging) {
                LOGGER.info("[preload virtual] 玩家={} 清除虚拟相机中心，切回玩家差集", player.getName().getString());
            }
        }
        st.virtualCenterActive = cameraMode;
        st.lastVirtualCenter = cameraMode ? cam : null;
        if (!player.isRemoved()) {
            cache.move(player);
        }
    }

    /** 清除虚拟相机中心，并让原版从相机中心切回玩家真实位置。 */
    private void clearVirtualCamera(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        ServerChunkCache cache = serverLevel.getChunkSource();
        ChunkMap chunkMap = cache.chunkMap;
        if (chunkMap instanceof CameraVirtualCenterAccess access) {
            access.immersiveCinematics$clearCameraSection(player.getUUID());
        }
        if (!player.isRemoved()) {
            cache.move(player);
        }
    }

    // ===== 锚点 =====

    /** 更新相机锚点；实体同步交给原版 ChunkMap 虚拟中心，不再走自建实体同步器。 */
    private void updateAnchors(UUID player, ServerLevel level, ChunkPos cam, PlayerState st) {
        CameraAnchorManager.INSTANCE.setAnchor(player, level, cam,
                st.cameraMobRadius, st.cameraMobSpawn, st.cameraMobAi);
    }

    private static String fmt(ChunkPos c) {
        return "[" + c.x + ", " + c.z + "]";
    }

    /** 单个玩家的预加载状态 */
    private static final class PlayerState {
        ServerPlayer player;
        String scriptId = "";
        boolean cameraMobSpawn = false;
        int cameraMobRadius = 2;
        boolean cameraMobAi = false;
        /** 上次实际应用到原版 ChunkMap 的虚拟相机中心状态，避免重复设置/移动 */
        ChunkPos lastVirtualCenter = null;
        boolean virtualCenterActive = false;
    }
}
