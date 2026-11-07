package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /** 跳过投票快照（不可变）：已投票人数 / 观看者总数 / 本人是否已投票。 */
    public static final class SkipVote {
        public final int voters;
        public final int total;
        /** 收包者本人是否已投过票（服务端逐人判定，同一批次内每人可能不同）。 */
        public final boolean youVoted;

        public SkipVote(int voters, int total, boolean youVoted) {
            this.voters = voters;
            this.total = total;
            this.youVoted = youVoted;
        }
    }

    /** 键为 {@code scriptId + '\0' + instanceId}（NUL 分隔，避免拼接歧义）；instanceId 为 null 时按空串存。 */
    private static final Map<String, SkipVote> SKIP_VOTES = new HashMap<>();

    /**
     * 本地已投票标记（键同 {@link #SKIP_VOTES}）。
     *
     * <p>与 {@link #SKIP_VOTES} 分开：投票后到服务端回包之间有一段往返窗口，期间缓存里还没有
     * {@code youVoted}；本地标记保证这段时间内长按不会重复发包、提示立即消失。
     */
    private static final Set<String> LOCAL_VOTED = new HashSet<>();

    private static String skipVoteKey(String scriptId, String instanceId) {
        return scriptId + '\0' + (instanceId != null ? instanceId : "");
    }

    /** 按 (scriptId, instanceId) 取票数快照；无记录返回 null。 */
    public static SkipVote getSkipVote(String scriptId, String instanceId) {
        return SKIP_VOTES.get(skipVoteKey(scriptId, instanceId));
    }

    /** 本地登记「本人已对该实例投过票」：长按不再发包、提示立即隐藏，不等服务端回包。 */
    public static void markVoted(String scriptId, String instanceId) {
        LOCAL_VOTED.add(skipVoteKey(scriptId, instanceId));
    }

    /** 本人是否已对该实例投过票：本地标记或服务端回执任一成立即为真。 */
    public static boolean hasVoted(String scriptId, String instanceId) {
        String key = skipVoteKey(scriptId, instanceId);
        if (LOCAL_VOTED.contains(key)) return true;
        SkipVote vote = SKIP_VOTES.get(key);
        return vote != null && vote.youVoted;
    }

    /** 覆盖该实例的投票进度：每次同步全量替换。 */
    public static void handleSkipVoteUpdate(S2CSkipVoteUpdatePacket packet) {
        SKIP_VOTES.put(skipVoteKey(packet.getScriptId(), packet.getInstanceId()),
                new SkipVote(packet.getVoterCount(), packet.getTotalViewers(), packet.isYouVoted()));
    }

    /** 清空某实例的票数与本地已投票标记；脚本结束 / 停止时按实例清零。 */
    public static void resetSkipVote(String scriptId, String instanceId) {
        String key = skipVoteKey(scriptId, instanceId);
        SKIP_VOTES.remove(key);
        LOCAL_VOTED.remove(key);
    }

    /** 清空全部投票进度与本地标记；世界退出 / 服务端停止时调用。 */
    public static void resetSkipVote() {
        SKIP_VOTES.clear();
        LOCAL_VOTED.clear();
    }

    /**
     * 播放服务端下发的脚本；{@code refId} 非空时回一条传输层 ACK。
     *
     * 回执与「是否真的开始播放」解耦：排队 / 被拒 / 解析失败也必须回，见下方行内说明。
     */
    public static void handlePlayScript(S2CPlayScriptPacket packet) {
        Minecraft.getInstance().execute(() -> {
            String instanceId = packet.getInstanceId();
            String refId = packet.getRefId();
            String scriptId = "";
            int jsonLength = -1;
            try {
                String json = packet.getScriptJson();
                jsonLength = json != null ? json.length() : 0;
                CinematicScript script = ScriptParser.parse(json);
                scriptId = script.getId();
                ClientScriptCache.putScript(scriptId, script);
                int result = CameraManager.INSTANCE.playCinematic(script, instanceId);
                LOGGER.info("Playing script from server: {} (result={}, instance={})",
                        scriptId, result, instanceId);
                if (result == 0) {
                    LOGGER.warn("Play request rejected (not in world): {}", scriptId);
                }
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited("Net", "client:play-script:" + instanceId, 5000L,
                        "处理 S2C 播放包失败（边界: packet:S2CPlayScript，scriptId=" + scriptId
                                + "，instanceId=" + instanceId + "，refId=" + refId
                                + "，json 长度=" + jsonLength + "）：本次播放已放弃，ACK 仍回", e);
            }
            // play 回执属传输层 ACK：排队 / 被拒 / 解析失败都必须回，否则服务端 AckTracker 超时重发 play 包导致重复入队。
            // started 恒为 false ——「已开始播放」由 CameraManager 在脚本真正开始时单独上报，这里绝不谎报。
            if (refId != null && !refId.isEmpty()) {
                String ackScriptId = scriptId;
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SPlaybackStarted(ack)",
                        () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                new C2SPlaybackStartedPacket(ackScriptId, instanceId, refId, false)));
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
            String scriptId = packet.getScriptId();
            String instanceId = packet.getInstanceId();
            String refId = packet.getRefId();
            try {
                if (scriptId.isEmpty()) {
                    CameraManager.INSTANCE.forceDeactivate();
                    LOGGER.info("Force-stopped all scripts by server");
                } else if (CameraManager.INSTANCE.stopScriptById(scriptId, instanceId)) {
                    // 按 (scriptId, instanceId) 定位实例：顶层 / 非顶层一律停，走渐出退场链
                    LOGGER.info("Stopped script by server: {} (instance={})", scriptId, instanceId);
                } else if (CameraManager.INSTANCE.cancelQueued(scriptId)) {
                    // 不在播 → 取消排队项；两者都不匹配 = 无目标，静默忽略
                    LOGGER.info("Cancelled queued script by server: {}", scriptId);
                } else {
                    LOGGER.info("Stop ignored (no playing instance / queued entry): {}", scriptId);
                }
                // stop 回执（refId 非空才回）；旧格式 stop 无 refId，其通知由 forceDeactivate 链路覆盖
                if (refId != null && !refId.isEmpty()) {
                    com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptFinished(stop ack)",
                            () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                    new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptFinishedPacket(
                                            scriptId, instanceId,
                                            com.immersivecinematics.immersive_cinematics.control.CompletionReason.STOPPED,
                                            refId)));
                }
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                // 停止是边界：异常不外抛（否则打断客户端 tick），但必须留完整栈便于定位
                LOGGER.error("Failed to handle stop script: script={}, instance={}, refId={}",
                        scriptId, instanceId, refId, e);
            } finally {
                resetSkipVote(scriptId, instanceId);
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
            String fileName = packet.getFileName();
            String fp = null;
            try {
                Path file = Paths.get("immersive_cinematics", "scripts").resolve(fileName);
                if (!Files.exists(file)) {
                    LOGGER.warn("Reload: 本地脚本文件不存在: {}", file);
                    return;
                }
                byte[] content = Files.readAllBytes(file);
                fp = ScriptFingerprint.of(content);
                String prev = scriptFingerprints.get(fileName);
                if (fp.equals(prev)) {
                    LOGGER.debug("Reload: 指纹未变化，忽略 {}", fileName);
                    return;
                }
                scriptFingerprints.put(fileName, fp);
                CinematicScript script = ScriptParser.parse(new String(content, StandardCharsets.UTF_8));
                ClientScriptCache.putScript(script.getId(), script);
                CameraManager.INSTANCE.playCinematic(script);
                LOGGER.info("Reloaded script from file: {}", fileName);
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited("Net", "client:script-reload:" + fileName, 5000L,
                        "处理 S2C 脚本重载失败（边界: packet:S2CScriptReload，fileName=" + fileName
                                + "，指纹=" + (fp != null ? fp : "未计算") + "）：本次重载已跳过", e);
            }
        });
    }
}
