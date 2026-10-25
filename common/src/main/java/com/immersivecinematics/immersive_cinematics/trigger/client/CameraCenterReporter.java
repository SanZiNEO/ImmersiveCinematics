package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.api.CameraApi;
import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraCenterPositionPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraCenterRequestPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.CinematicC2SPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 相机中心同步客户端上报器，由客户端 tick 驱动。
 *
 * 与原版差集思路一致：脚本激活后持续上报中心位置，不做「是否远离玩家」的门控，服务端自行算差集。
 * 位置上报按 {@link Config#cameraCenterReportInterval} tick 节流，相机活跃态翻转时立即补发一次；
 * 相机空档 / 无活跃 CAMERA 片段时改报玩家位置，让服务端把窗口切回玩家区。
 *
 * 上报全是单向的：不等服务端响应，服务端未处理也不阻塞播放。
 */
public final class CameraCenterReporter {

    /** 由客户端 tick 驱动的唯一实例。 */
    public static final CameraCenterReporter INSTANCE = new CameraCenterReporter();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraCenter");

    /** 当前已上报 START 的脚本 id；空串 = 未激活。 */
    private String lastScript = "";
    /** 距上次位置上报的 tick 数。 */
    private int tickCounter = 0;

    /** 上一刷新是否有活跃 CAMERA 片段；翻转时立即补发位置 */
    private boolean lastHadActiveCamera = false;

    private CameraCenterReporter() {}

    /** 断线 / 退出世界时清空上报状态：不发 END（连接已不可用），保证下个世界从干净状态开始。 */
    public void reset() {
        lastScript = "";
        lastHadActiveCamera = false;
        tickCounter = 0;
    }

    /** 统一 C2S 发送：断线 / 退出世界时只记日志，不打断客户端 tick。 */
    private static void send(String label, CinematicC2SPacket packet) {
        NetworkGuard.sendToServer(label, () -> NetworkHandler.sendToServer(packet));
    }

    /**
     * 每客户端 tick 调用：脚本切换发 START，播放中按间隔发位置，结束 / 停止 / 预览发 END。
     *
     * @param mc 客户端实例（读玩家位置）
     */
    public void tick(Minecraft mc) {
        // 读侧走 api 面（只读查询）；其余查询 / 控制仍走门面
        CameraApi cam = CameraManager.INSTANCE;
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
            boolean activeCamera = cam.hasActiveCameraClip();
            CameraState camState = cam.getCameraState();
            Vec3 pos;
            if (activeCamera && camState != null) {
                pos = camState.position();
            } else {
                // 空档/无镜头片段：相机不覆盖，实际是玩家视角，报玩家位置让服务端差集切回玩家区
                if (mc.player == null) {
                    releaseIfNeeded();
                    return;
                }
                pos = mc.player.position();
            }
            int bx = (int) Math.floor(pos.x);
            int bz = (int) Math.floor(pos.z);
            if (!sid.equals(lastScript)) {
                lastScript = sid;
                tickCounter = 0;
                lastHadActiveCamera = activeCamera;
                if (Config.debugLogging) {
                    LOGGER.info("[camera center] START script={} 位置=({},{}) 相机块=({},{}) cameraMode={}",
                            sid, bx, bz, bx >> 4, bz >> 4, activeCamera);
                }
                send("C2SCameraCenterRequest", new C2SCameraCenterRequestPacket(
                        C2SCameraCenterRequestPacket.MODE_START, sid, bx, bz, activeCamera));
                return;
            }
            tickCounter++;
            boolean activeChanged = activeCamera != lastHadActiveCamera;
            if (activeChanged) lastHadActiveCamera = activeCamera;
            if (tickCounter % Math.max(1, Config.cameraCenterReportInterval) == 0 || activeChanged) {
                if (Config.debugLogging) {
                    LOGGER.info("[camera center] POS script={} 位置=({},{}) 相机块=({},{}) cameraMode={} activeChanged={}",
                            sid, bx, bz, bx >> 4, bz >> 4, activeCamera, activeChanged);
                }
                send("C2SCameraCenterPosition",
                        new C2SCameraCenterPositionPacket(bx, bz, activeCamera));
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
        reset();
        if (Config.debugLogging) {
            LOGGER.info("[camera center] END script={}", scriptId);
        }
        send("C2SCameraCenterEnd", new C2SCameraCenterRequestPacket(
                C2SCameraCenterRequestPacket.MODE_END, scriptId, 0, 0, false));
    }

    /** 只有脚本时间轴存在非空 CAMERA 轨道才上报；纯 OVERLAY/EVENT 脚本不触发 */
    private static boolean hasCameraTrack(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        return track.isPresent() && !track.get().getClips().isEmpty();
    }
}
