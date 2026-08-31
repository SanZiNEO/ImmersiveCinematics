package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadPositionPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * 区块预加载客户端请求器 — 由 ClientEventHandler 客户端 tick 驱动。
 * <p>
 * 采用与服务端一致的原版差集思路：脚本激活后持续上报相机位置，
 * 不做“是否远离玩家”的门控；服务端自行计算应加载/补发的差集。
 * <ul>
 *   <li>脚本开始（新 script id）→ 发 PRELOAD（初始窗口 = 相机当前位置）</li>
 *   <li>播放中 → 每 {@link Config#preloadReportInterval} tick 上报相机位置（滑动窗口跟随）</li>
 *   <li>脚本结束/停止/预览 → 发 RELEASE 释放</li>
 * </ul>
 */
public final class PreloadRequester {

    public static final PreloadRequester INSTANCE = new PreloadRequester();

    private String lastScript = "";
    private int tickCounter = 0;
    private boolean preloadActive = false;

    /** 已发送预热的脚本/片段目标，避免每 tick 重复发包 */
    private String prewarmTargetKey = "";

    /** 上一刷新是否有活跃 CAMERA 片段；空档/无镜头切换时立即上报玩家位置 */
    private boolean lastHadActiveCamera = false;

    private PreloadRequester() {}

    /** 预载是否真的激活过（用于退出时是否触发全量渲染重建） */
    public boolean isPreloadActive() {
        return preloadActive;
    }

    public void tick(Minecraft mc) {
        CameraManager cam = CameraManager.INSTANCE;
        if (!Config.preloadEnabled) {
            releaseIfNeeded();
            return;
        }
        ScriptPlayer sp = cam.getScriptPlayer();
        if (cam.isActive() && sp.isPlaying() && !cam.isPreviewMode()) {
            CinematicScript script = sp.getScript();
            String sid = script != null ? script.getId() : "";
            if (sid.isEmpty()) {
                releaseIfNeeded();
                return;
            }
            if (!isScriptPreloadEnabled(script)) {
                releaseIfNeeded();
                return;
            }
            if (!hasCameraTrack(script)) {
                releaseIfNeeded();
                return;
            }
            boolean activeCamera = cam.hasActiveCameraClip();
            Vec3 pos;
            if (activeCamera) {
                pos = cam.getPath().getPosition();
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
                preloadActive = true;
                prewarmTargetKey = "";
                lastHadActiveCamera = activeCamera;
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_PRELOAD, sid, bx, bz, Config.preloadWindowRadius,
                                cam.getCameraYaw(), mc.options.renderDistance().get(),
                                script.getMeta().isCameraMobSpawn(), script.getMeta().getCameraMobRadius(),
                                script.getMeta().isCameraMobAi()));
                return;
            }
            tickCounter++;
            boolean activeChanged = activeCamera != lastHadActiveCamera;
            if (activeChanged) lastHadActiveCamera = activeCamera;
            if ((tickCounter % Math.max(1, Config.preloadReportInterval) == 0 && preloadActive) || activeChanged) {
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new C2SPreloadPositionPacket(bx, bz, cam.getCameraYaw()));
            }
            tickPrewarm(mc, sid, script, sp);
        } else {
            releaseIfNeeded();
        }
    }

    private void releaseIfNeeded() {
        if (lastScript.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        // 不在游戏内/连接已关闭时只清状态，绝不发包（异常断线/世界退出场景）
        if (mc.level == null || mc.getConnection() == null) {
            lastScript = "";
            preloadActive = false;
            prewarmTargetKey = "";
            lastHadActiveCamera = false;
            return;
        }
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_RELEASE, lastScript, 0, 0, 0, 0f, 0,
                        C2SPreloadRequestPacket.DEFAULT_CAMERA_MOB_SPAWN,
                        C2SPreloadRequestPacket.DEFAULT_CAMERA_MOB_RADIUS,
                        C2SPreloadRequestPacket.DEFAULT_CAMERA_MOB_AI));
        lastScript = "";
        preloadActive = false;
        prewarmTargetKey = "";
        lastHadActiveCamera = false;
    }

    /** 预热：接近下个 CAMERA 片段时，把下一片段首帧位置发给服务端提前加载 */
    private void tickPrewarm(Minecraft mc, String sid, CinematicScript script, ScriptPlayer sp) {
        if (!preloadActive || script == null) return;
        Optional<TimelineTrack> camTrack = script.getTimeline().getCameraTrack();
        if (camTrack.isEmpty()) return;
        List<Clip> clips = camTrack.get().getClips();
        if (clips.size() < 2) return;

        float elapsed = sp.getElapsedSeconds();
        int activeIdx = findActiveCameraClipIndex(clips, elapsed);
        if (activeIdx < 0 || activeIdx + 1 >= clips.size()) return;

        Clip next = clips.get(activeIdx + 1);
        float lead = next.getStartTime() - elapsed;
        // 仍在提前量窗口内；lead<0 说明已切过去，不再发旧目标
        if (lead > Config.preloadPrewarmLeadSeconds || lead < -0.5f) return;

        Vec3 target = computeClipStartWorldPos(next, sp.getOriginPos());
        if (target == null) return;

        String key = sid + "|" + activeIdx + "|" + ((long) Math.floor(target.x)) + "," + ((long) Math.floor(target.z));
        if (key.equals(prewarmTargetKey)) return;

        int bx = (int) Math.floor(target.x);
        int bz = (int) Math.floor(target.z);
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_PREWARM, sid, bx, bz,
                        Config.preloadPrewarmRadius,
                        CameraManager.INSTANCE.getCameraYaw(), mc.options.renderDistance().get(),
                        script.getMeta().isCameraMobSpawn(), script.getMeta().getCameraMobRadius(),
                        script.getMeta().isCameraMobAi()));
        prewarmTargetKey = key;
    }

    private static int findActiveCameraClipIndex(List<Clip> clips, float elapsed) {
        int active = -1;
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            if (elapsed >= c.getStartTime() && elapsed < c.getWindowEnd()) {
                active = i;
            }
        }
        return active;
    }

    /** 静态可解析的下一片段首帧世界坐标；结构/方块/实体/facing 等动态基准返回 null（首版跳过） */
    private static Vec3 computeClipStartWorldPos(Clip clip, Vec3 originPos) {
        if (clip.getKeyframes().isEmpty()) return null;
        Keyframe kf = clip.getKeyframes().get(0);
        PositionData pd = kf.getPosition();
        if (pd == null) return null;
        if (!pd.isRelative()) return pd.toVec3();
        if (pd.isOriginCoordinate()) {
            return new Vec3(pd.getOriginX() + pd.getDx(), pd.getOriginY() + pd.getDy(), pd.getOriginZ() + pd.getDz());
        }
        if (pd.getOriginStructure() != null || pd.isOriginBlock() || pd.isFacingRelative()) return null;
        return originPos.add(pd.toVec3());
    }

    /** 只有脚本时间轴存在非空 CAMERA 轨道才进入预加载；纯 OVERLAY/EVENT 脚本不触发 */
    private static boolean hasCameraTrack(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        return track.isPresent() && !track.get().getClips().isEmpty();
    }

    /** 脚本级开关：meta.preload 缺省/true = 启用；false = 本脚本关闭预加载（不发任何预载请求） */
    private static boolean isScriptPreloadEnabled(CinematicScript script) {
        if (script == null) return true;
        Object raw = script.getRawJson();
        if (raw instanceof String s && !s.isEmpty()) {
            JsonObject root = JsonParser.parseString(s).getAsJsonObject();
            if (root.has("meta") && root.get("meta").isJsonObject()) {
                JsonObject meta = root.getAsJsonObject("meta");
                if (meta.has("preload")) {
                    return meta.get("preload").getAsBoolean();
                }
            }
        }
        return true;
    }
}
