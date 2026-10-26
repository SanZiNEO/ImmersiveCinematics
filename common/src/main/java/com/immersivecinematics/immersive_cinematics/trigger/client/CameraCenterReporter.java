package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraCenterPositionPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraCenterRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.CinematicC2SPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 相机中心同步客户端上报器，由客户端 tick 驱动。
 *
 * 与原版差集思路一致：脚本激活后持续上报中心位置，不做「是否远离玩家」的门控，服务端自行算差集。
 * 位置上报每 tick 至多一包：距上次上报达到 {@link Config#cameraCenterReportInterval} tick，或位移 ≥1 格，
 * 或当前维度相机态翻转时发送；没有当前维度 CAMERA lane 时改报玩家位置（含 y），让服务端把窗口切回玩家区。
 * 每次新 START 生成新的 sessionId，END 回传同一 sessionId，服务端据此忽略旧播放的迟到包。
 *
 * <p><b>上报的是「当前维度中心」，不是「主相机中心」。</b>每条 CAMERA lane 都是独立相机，合成顺序只决定
 * 叠放；这里按 lane 顺序取当前维度的第一条（最底层 / 背景）lane，用它自己的相机眼位作为
 * {@code CameraCenterManager} 的当前维度中心。目标维度的预取景 lane 即使从 0s 起 {@code opacity=0} 也仍是
 * 活跃 lane，但它不参与这里的选择——目标维度由 {@link CameraViewReporter} 按 view 独立上报。没有当前维度
 * lane 时回落到玩家位置并令 {@code cameraMode=false}，服务端据此把窗口切回玩家区。
 *
 * <p>上报全是单向的：不等服务端响应，服务端未处理也不阻塞播放。
 */
public final class CameraCenterReporter {

    /** 由客户端 tick 驱动的唯一实例。 */
    public static final CameraCenterReporter INSTANCE = new CameraCenterReporter();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraCenter");

    /** 当前已上报 START 的脚本 id；空串 = 未激活。 */
    private String lastScript = "";
    /** 当前播放会话 id；每次新 START 自增，reset() 清零。 */
    private long sessionId = 0L;
    /** 距上次位置上报的 tick 数。 */
    private int tickCounter = 0;
    /** 上一次实际上报的位置（位移阈值比较用）。 */
    private Vec3 lastReported = Vec3.ZERO;

    /** 上一刷新是否有当前维度 CAMERA lane（= 上报中心是否来自相机）；翻转时立即补发位置。 */
    private boolean lastHadCurrentDimensionLane = false;

    private CameraCenterReporter() {}

    /** 断线 / 退出世界时清空上报状态：不发 END（连接已不可用），保证下个世界从干净状态开始。 */
    public void reset() {
        lastScript = "";
        sessionId = 0L;
        lastHadCurrentDimensionLane = false;
        tickCounter = 0;
        lastReported = Vec3.ZERO;
    }

    /** 统一 C2S 发送：断线 / 退出世界时只记日志，不打断客户端 tick。 */
    private static void send(String label, CinematicC2SPacket packet) {
        NetworkGuard.sendToServer(label, () -> NetworkHandler.sendToServer(packet));
    }

    /**
     * 每客户端 tick 调用：脚本切换发 START，播放中按间隔 / 位移 / 翻转发位置，结束 / 停止 / 预览发 END。
     *
     * @param mc 客户端实例（读玩家位置）
     */
    public void tick(Minecraft mc) {
        PlaybackInstance instance = CameraManager.INSTANCE.activeInstance();
        ScriptPlayer sp = instance != null ? instance.player() : null;
        if (sp != null && sp.isPlaying() && !CameraManager.INSTANCE.isPreviewMode()) {
            CinematicScript script = sp.getScript();
            String sid = script != null ? script.getId() : "";
            if (sid.isEmpty()) {
                releaseIfNeeded();
                return;
            }
            if (!hasCameraTrack(script)) {
                releaseIfNeeded();
                return;
            }
            if (mc.level == null) {
                releaseIfNeeded();
                return;
            }
            // 当前维度中心 = 该维度第一条（最底层 / 背景）CAMERA lane 的相机眼位。
            // 不用 CameraManager.getCameraState()：它取顶层实例的覆盖链输出（通常与合成顺序末位 lane 同源），
            // 跨维度预取景 lane 从 0s 起 opacity=0 也仍是活跃 lane，会把服务端当前维度中心带到目标维度。
            // 也不用 LaneRenderer.activeViews()：它只收 dimension 非空的 lane（空维度 = 当前维度的 lane 被过滤），
            // 选不到以空维度注册的当前维度中心。
            // 玩家当前维度从玩家实体读：即使调用点落在 lane pass 内（mc.level 被换到目标视图）也不会选错维度
            ClientLevel playerLevel = mc.player != null && mc.player.level() instanceof ClientLevel level
                    ? level : mc.level;
            String currentDimension = DimensionViews.currentDimensionId(playerLevel);
            Vec3 laneCenter = LaneRenderer.INSTANCE.currentDimensionCenter(currentDimension);
            // 维度未知（无世界）时不认相机中心，回落玩家 / 释放
            boolean cameraMode = laneCenter != null && !currentDimension.isEmpty();
            Vec3 pos;
            if (cameraMode) {
                pos = laneCenter;
            } else {
                // 没有当前维度 CAMERA lane：相机不覆盖当前维度，实际是玩家视角，报玩家位置让服务端差集切回玩家区
                if (mc.player == null) {
                    releaseIfNeeded();
                    return;
                }
                pos = mc.player.position();
            }
            if (!sid.equals(lastScript)) {
                lastScript = sid;
                sessionId++;
                tickCounter = 0;
                lastReported = pos;
                lastHadCurrentDimensionLane = cameraMode;
                if (Config.debugLogging) {
                    LOGGER.info("[camera center] START script={} session={} 位置=({},{},{}) 相机块=({},{}) cameraMode={}",
                            sid, sessionId, pos.x, pos.y, pos.z, chunk(pos.x), chunk(pos.z), cameraMode);
                }
                send("C2SCameraCenterRequest", new C2SCameraCenterRequestPacket(
                        C2SCameraCenterRequestPacket.MODE_START, sid, sessionId,
                        pos.x, pos.y, pos.z, cameraMode));
                return;
            }
            tickCounter++;
            boolean cameraModeChanged = cameraMode != lastHadCurrentDimensionLane;
            if (cameraModeChanged) lastHadCurrentDimensionLane = cameraMode;
            boolean moved = pos.distanceToSqr(lastReported) >= 1.0;
            if (tickCounter >= Math.max(1, Config.cameraCenterReportInterval) || moved || cameraModeChanged) {
                tickCounter = 0;
                lastReported = pos;
                if (Config.debugLogging) {
                    LOGGER.info("[camera center] POS script={} session={} 位置=({},{},{}) 相机块=({},{}) cameraMode={} activeChanged={}",
                            sid, sessionId, pos.x, pos.y, pos.z, chunk(pos.x), chunk(pos.z), cameraMode, cameraModeChanged);
                }
                send("C2SCameraCenterPosition",
                        new C2SCameraCenterPositionPacket(sessionId, pos.x, pos.y, pos.z, cameraMode));
            }
        } else {
            releaseIfNeeded();
        }
    }

    private void releaseIfNeeded() {
        if (lastScript.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        // 不在游戏内/连接已关闭时只清状态，绝不发包（异常断线/世界退出场景）
        if (mc.level == null || mc.getConnection() == null) {
            reset();
            return;
        }
        // 先清状态再发包：发送失败（断线/退出世界）也不会让 END 每 tick 重试
        String scriptId = lastScript;
        long endedSession = sessionId;
        reset();
        if (Config.debugLogging) {
            LOGGER.info("[camera center] END script={} session={}", scriptId, endedSession);
        }
        send("C2SCameraCenterEnd", new C2SCameraCenterRequestPacket(
                C2SCameraCenterRequestPacket.MODE_END, scriptId, endedSession, 0.0, 0.0, 0.0, false));
    }

    /** 只有脚本时间轴存在非空 CAMERA 轨道才上报；纯 OVERLAY/EVENT 脚本不触发 */
    private static boolean hasCameraTrack(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        return track.isPresent() && !track.get().getClips().isEmpty();
    }

    private static int chunk(double coord) {
        return Mth.floor(coord) >> 4;
    }
}
