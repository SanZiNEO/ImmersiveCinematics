package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadPositionPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

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
            Vec3 pos = cam.getPath().getPosition();
            int bx = (int) Math.floor(pos.x);
            int bz = (int) Math.floor(pos.z);
            if (!sid.equals(lastScript)) {
                lastScript = sid;
                tickCounter = 0;
                preloadActive = true;
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_PRELOAD, sid, bx, bz, Config.preloadWindowRadius,
                                cam.getCameraYaw(), mc.options.renderDistance().get(),
                                script.getMeta().isCameraMobSpawn(), script.getMeta().getCameraMobRadius(),
                                script.getMeta().isCameraMobAi()));
                return;
            }
            tickCounter++;
            if (tickCounter % Math.max(1, Config.preloadReportInterval) == 0 && preloadActive) {
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new C2SPreloadPositionPacket(bx, bz, cam.getCameraYaw()));
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
            lastScript = "";
            preloadActive = false;
            return;
        }
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_RELEASE, lastScript, 0, 0, 0, 0f, 0,
                        C2SPreloadRequestPacket.DEFAULT_CAMERA_MOB_SPAWN,
                        C2SPreloadRequestPacket.DEFAULT_CAMERA_MOB_RADIUS,
                        C2SPreloadRequestPacket.DEFAULT_CAMERA_MOB_AI));
        lastScript = "";
        preloadActive = false;
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
