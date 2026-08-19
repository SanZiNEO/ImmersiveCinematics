package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadPositionPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPreloadRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 区块预加载客户端请求器 — 由 ClientEventHandler 客户端 tick 驱动：
 * <ul>
 *   <li>脚本开始（新 script id）→ 发 PRELOAD（初始窗口 = 相机当前位置 ± 窗口半径）</li>
 *   <li>播放中 → 每 {@link Config#preloadReportInterval} tick 上报相机位置（滑动窗口跟随）</li>
 *   <li>脚本结束/停止/预览 → 发 RELEASE 释放</li>
 *   <li>全局 {@link Config#preloadEnabled} 关闭时不发任何请求（服务端再兜底强制）</li>
 * </ul>
 * TODO: meta.preload 逐脚本开关（默认跟随全局）接入 ScriptMeta。
 */
public final class PreloadRequester {

    public static final PreloadRequester INSTANCE = new PreloadRequester();

    private String lastScript = "";
    private int tickCounter = 0;

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
                tickCounter = 0;
                new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_PRELOAD, sid, bx, bz, Config.preloadWindowRadius)
                        .sendToServer();
                return;
            }
            tickCounter++;
            if (tickCounter % Math.max(1, Config.preloadReportInterval) == 0) {
                new C2SPreloadPositionPacket(bx, bz).sendToServer();
            }
        } else {
            releaseIfNeeded();
        }
    }

    private void releaseIfNeeded() {
        if (lastScript.isEmpty()) return;
        new C2SPreloadRequestPacket(C2SPreloadRequestPacket.MODE_RELEASE, lastScript, 0, 0, 0).sendToServer();
        lastScript = "";
    }
}
