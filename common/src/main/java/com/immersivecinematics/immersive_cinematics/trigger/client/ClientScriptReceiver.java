package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPlaybackStartedPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CScriptReloadPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CSkipVoteUpdatePacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.ScriptFingerprint;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ClientScriptReceiver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** N2b：本地脚本文件指纹缓存（fileName → 上次加载指纹），指纹相同则忽略 */
    private static final Map<String, String> scriptFingerprints = new HashMap<>();

    /** 跳过投票进度缓存（由 S2CSkipVoteUpdatePacket 更新） */
    private static int cachedVoterCount = 0;
    private static int cachedTotalViewers = 0;

    public static int getSkipVoterCount() { return cachedVoterCount; }
    public static int getSkipTotalViewers() { return cachedTotalViewers; }

    public static void handleSkipVoteUpdate(S2CSkipVoteUpdatePacket packet) {
        cachedVoterCount = packet.getVoterCount();
        cachedTotalViewers = packet.getTotalViewers();
    }

    public static void resetSkipVote() {
        cachedVoterCount = 0;
        cachedTotalViewers = 0;
    }

    public static void handlePlayScript(S2CPlayScriptPacket packet) {
        Minecraft.getInstance().execute(() -> {
            try {
                CinematicScript script = ScriptParser.parse(packet.getScriptJson());
                CameraManager.INSTANCE.playCinematic(script);
                LOGGER.info("Playing script from server: {}", script.getId());
                // N1：play 回执（refId 随包回填）
                new C2SPlaybackStartedPacket(script.getId(), packet.getRefId()).sendToServer();
            } catch (Exception e) {
                LOGGER.error("Failed to parse script from server", e);
            }
        });
    }

    public static void handleStopScript(S2CStopScriptPacket packet) {
        Minecraft.getInstance().execute(() -> {
            resetSkipVote();
            String scriptId = packet.getScriptId();
            if (scriptId.isEmpty()) {
                CameraManager.INSTANCE.forceDeactivate();
                LOGGER.info("Force-stopped all scripts by server");
            } else {
                String activeId = CameraManager.INSTANCE.getActiveScriptId();
                if (activeId != null && activeId.equals(scriptId)) {
                    CameraManager.INSTANCE.forceDeactivate();
                    LOGGER.info("Stopped script by server: {}", scriptId);
                }
            }
            // N1：stop 回执（refId 非空才回；旧格式 stop 由 forceDeactivate 现有通知链路覆盖）；
            // 经 NetworkGuard 防断线崩溃
            if (packet.getRefId() != null && !packet.getRefId().isEmpty()) {
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptFinished(stop ack)",
                        () -> new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptFinishedPacket(
                                scriptId, com.immersivecinematics.immersive_cinematics.control.CompletionReason.STOPPED,
                                packet.getRefId()).sendToServer());
            }
        });
    }

    /**
     * N2b：编辑同步重载 — 读本地全局脚本目录同名文件，指纹相同则忽略，变化则解析并播放一次。
     * <p>
     * 目录策略（用户指定）：编辑器模式统一用全局脚本目录（各端环境下的 immersive_cinematics/scripts）。
     */
    public static void handleScriptReload(S2CScriptReloadPacket packet) {
        Minecraft.getInstance().execute(() -> {
            try {
                String fileName = packet.getFileName();
                Path file = Paths.get("immersive_cinematics", "scripts").resolve(fileName);
                if (!Files.exists(file)) {
                    LOGGER.warn("Reload: 本地脚本文件不存在: {}", file);
                    return;
                }
                byte[] content = Files.readAllBytes(file);
                String fp = ScriptFingerprint.of(content);
                String prev = scriptFingerprints.get(fileName);
                if (fp.equals(prev)) {
                    LOGGER.debug("Reload: 指纹未变化，忽略 {}", fileName);
                    return;
                }
                scriptFingerprints.put(fileName, fp);
                CinematicScript script = ScriptParser.parse(new String(content, StandardCharsets.UTF_8));
                CameraManager.INSTANCE.playCinematic(script);
                LOGGER.info("Reloaded script from file: {}", fileName);
            } catch (Exception e) {
                LOGGER.error("Failed to reload script from file", e);
            }
        });
    }
}
