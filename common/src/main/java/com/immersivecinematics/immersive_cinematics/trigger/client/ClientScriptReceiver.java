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

/**
 * 客户端 S2C 脚本指令接收与分发：播放 / 停止 / 编辑同步重载 / 跳过投票进度。
 *
 * 播放 / 停止 / 重载经 {@code Minecraft.execute()} 切到客户端主线程执行；解析或播放异常记 error
 * 日志、不向上抛，避免单个坏包打断客户端 tick。
 */
public class ClientScriptReceiver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本地脚本文件指纹缓存（fileName → 上次加载指纹）；指纹相同则跳过重载。 */
    private static final Map<String, String> scriptFingerprints = new HashMap<>();

    /** 跳过投票进度（由 S2CSkipVoteUpdatePacket 覆盖）；resetSkipVote() 清零。 */
    private static int cachedVoterCount = 0;
    private static int cachedTotalViewers = 0;

    /** 最近一次同步的已投跳过票人数。 */
    public static int getSkipVoterCount() { return cachedVoterCount; }
    /** 最近一次同步的观众总数（0 = 无进行中的投票）。 */
    public static int getSkipTotalViewers() { return cachedTotalViewers; }

    /** 覆盖缓存的投票进度：每次同步全量替换。 */
    public static void handleSkipVoteUpdate(S2CSkipVoteUpdatePacket packet) {
        cachedVoterCount = packet.getVoterCount();
        cachedTotalViewers = packet.getTotalViewers();
    }

    /** 清零投票进度；脚本结束 / 停止时调用。 */
    public static void resetSkipVote() {
        cachedVoterCount = 0;
        cachedTotalViewers = 0;
    }

    /**
     * 播放服务端下发的脚本；{@code refId} 非空时回一条传输层 ACK。
     *
     * 回执与「是否真的开始播放」解耦：排队 / 被拒也必须回，见下方行内说明。
     */
    public static void handlePlayScript(S2CPlayScriptPacket packet) {
        Minecraft.getInstance().execute(() -> {
            try {
                CinematicScript script = ScriptParser.parse(packet.getScriptJson());
                int result = CameraManager.INSTANCE.playCinematic(script, packet.getInstanceId());
                LOGGER.info("Playing script from server: {} (result={}, instance={})",
                        script.getId(), result, packet.getInstanceId());
                if (result == 0) {
                    LOGGER.warn("Play request rejected (not in world): {}", script.getId());
                }
                // play 回执属传输层 ACK：排队 / 被拒也必须回，否则服务端 AckTracker 超时重发 play 包导致重复入队。
                // started 恒为 false ——「已开始播放」由 CameraManager 在脚本真正开始时单独上报，这里绝不谎报。
                if (packet.getRefId() != null && !packet.getRefId().isEmpty()) {
                    com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SPlaybackStarted(ack)",
                            () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                    new C2SPlaybackStartedPacket(script.getId(), packet.getInstanceId(), packet.getRefId(), false)));
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse script from server", e);
            }
        });
    }

    /**
     * 停止服务端指定脚本：先按实例定位，再回退到取消排队项，两者都不匹配则忽略。
     *
     * refId 非空时回一条 stop ACK；旧格式 stop 无 refId，其通知由 forceDeactivate 链路覆盖。
     */
    public static void handleStopScript(S2CStopScriptPacket packet) {
        Minecraft.getInstance().execute(() -> {
            resetSkipVote();
            String scriptId = packet.getScriptId();
            if (scriptId.isEmpty()) {
                CameraManager.INSTANCE.forceDeactivate();
                LOGGER.info("Force-stopped all scripts by server");
            } else if (CameraManager.INSTANCE.stopScriptById(scriptId, packet.getInstanceId())) {
                // 按 (scriptId, instanceId) 定位实例：顶层 / 非顶层一律停，走渐出退场链
                LOGGER.info("Stopped script by server: {} (instance={})", scriptId, packet.getInstanceId());
            } else if (CameraManager.INSTANCE.cancelQueued(scriptId)) {
                // 不在播 → 取消排队项；两者都不匹配 = 无目标，静默忽略
                LOGGER.info("Cancelled queued script by server: {}", scriptId);
            } else {
                LOGGER.info("Stop ignored (no playing instance / queued entry): {}", scriptId);
            }
            // stop 回执（refId 非空才回）；旧格式 stop 无 refId，其通知由 forceDeactivate 链路覆盖
            if (packet.getRefId() != null && !packet.getRefId().isEmpty()) {
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptFinished(stop ack)",
                        () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptFinishedPacket(
                                        scriptId, packet.getInstanceId(),
                                        com.immersivecinematics.immersive_cinematics.control.CompletionReason.STOPPED,
                                        packet.getRefId())));
            }
        });
    }

    /**
     * 编辑同步重载：读本地脚本目录下同名文件，指纹未变则忽略，变化则解析并播放一次。
     *
     * 目录固定为全局脚本目录 {@code immersive_cinematics/scripts}（相对游戏运行目录）。
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
