package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadPositionPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 区块预加载客户端请求器 — 由 ClientEventHandler 客户端 tick 驱动：
 * <ul>
 *   <li>脚本开始（新 script id）→ 发 PRELOAD（初始窗口 = 相机当前位置 ± 窗口半径）</li>
 *   <li>播放中 → 每 {@link Config#preloadReportInterval} tick 上报相机位置（滑动窗口跟随）</li>
 *   <li>当前片段剩余 ≤ {@link Config#preloadPrewarmLeadSeconds} 且下一片段起点离玩家较远 → 发 PREWARM（慢速预载）</li>
 *   <li>脚本结束/停止/预览 → 发 RELEASE 释放</li>
 * </ul>
 */
public final class PreloadRequester {

    public static final PreloadRequester INSTANCE = new PreloadRequester();

    private String lastScript = "";
    private int tickCounter = 0;
    private String lastPrewarm = "";

    private PreloadRequester() {}

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
            Vec3 pos = cam.getPath().getPosition();
            int bx = (int) Math.floor(pos.x);
            int bz = (int) Math.floor(pos.z);
            if (!sid.equals(lastScript)) {
                lastScript = sid;
                lastPrewarm = "";
                tickCounter = 0;
                new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_PRELOAD, sid, bx, bz, Config.preloadWindowRadius,
                        cam.getCameraYaw(), mc.options.renderDistance().get()).sendToServer();
                return;
            }
            tickCounter++;
            if (tickCounter % Math.max(1, Config.preloadReportInterval) == 0) {
                new C2SPreloadPositionPacket(bx, bz, cam.getCameraYaw()).sendToServer();
            }
            maybePrewarm(mc, sp);
        } else {
            releaseIfNeeded();
        }
    }

    /**
     * lookahead 预载：当前片段剩余 ≤ lead 秒时，若下一 CAMERA 片段起点离玩家较远，发 PREWARM（服务端慢速加票，不发包）。
     * v1 只支持 position 的 x/z（绝对）或 dx/dz（相对玩家激活原点）；其他模式暂跳过。
     */
    private void maybePrewarm(Minecraft mc, ScriptPlayer sp) {
        if (Config.preloadPrewarmLeadSeconds <= 0) return;
        CinematicScript script = sp.getScript();
        if (script == null || script.getTimeline() == null) return;
        float elapsed = (float) CameraManager.INSTANCE.getGameTimeSeconds();
        List<Clip> clips = new ArrayList<>();
        for (TimelineTrack t : script.getTimeline().getTracks()) {
            if (t.getType() == TrackType.CAMERA) clips.addAll(t.getClips());
        }
        if (clips.size() < 2) return;
        clips.sort(Comparator.comparingDouble((Clip c) -> (double) c.getStartTime()));

        Clip current = null;
        Clip next = null;
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            if (elapsed >= c.getStartTime() && elapsed < c.getStartTime() + c.getDuration()) {
                current = c;
                if (i + 1 < clips.size()) next = clips.get(i + 1);
                break;
            }
        }
        if (current == null || next == null) return;
        if (elapsed < current.getStartTime() + current.getDuration() - Config.preloadPrewarmLeadSeconds) return;

        Keyframe kf = next.getKeyframes().isEmpty() ? null : next.getKeyframes().get(0);
        if (kf == null) return;
        Object posObj = kf.getObject("position");
        if (!(posObj instanceof PositionData pd)) return;
        if (pd.isFacingRelative()) return; // v1 暂不支持 fwd/up/right，跳过
        Vec3 origin = sp.getOriginPos();
        double tx;
        double tz;
        if (pd.isRelative()) {
            if (origin == null) return;
            tx = origin.x + pd.getDx();
            tz = origin.z + pd.getDz();
        } else {
            tx = pd.getX();
            tz = pd.getZ();
        }
        int targetCx = (int) Math.floor(tx) >> 4;
        int targetCz = (int) Math.floor(tz) >> 4;
        if (mc.player == null) return;
        int playerCx = mc.player.blockPosition().getX() >> 4;
        int playerCz = mc.player.blockPosition().getZ() >> 4;
        int far = Math.max(2, Config.preloadFarViewCenterThreshold);
        if (Math.abs(targetCx - playerCx) <= far && Math.abs(targetCz - playerCz) <= far) {
            return; // 下一片段在玩家附近，无需预载
        }
        String key = script.getId() + "|" + targetCx + "," + targetCz;
        if (key.equals(lastPrewarm)) return;
        lastPrewarm = key;
        new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_PREWARM, script.getId(),
                (int) tx, (int) tz, Config.preloadPrewarmRadius, 0f,
                mc.options.renderDistance().get()).sendToServer();
    }

    private void releaseIfNeeded() {
        if (lastScript.isEmpty()) return;
        new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_RELEASE, lastScript, 0, 0, 0, 0f, 0).sendToServer();
        lastScript = "";
        lastPrewarm = "";
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
