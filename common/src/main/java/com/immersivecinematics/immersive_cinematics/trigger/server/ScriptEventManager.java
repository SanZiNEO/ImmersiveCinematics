package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CSkipVoteUpdatePacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ScriptEventManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ScriptEventManager INSTANCE = new ScriptEventManager();

    /**
     * 播放账本：scriptId → 实例 id → 该实例的播放状态。
     * <p>
     * §3.7：观看者、跳过投票、事件时间线全部按<b>实例</b>维护。单实例语义下每个脚本至多一条
     * （实例 id 为空串时即“该脚本的唯一实例”），行为与实例化之前逐点一致；同一脚本的不同播放请求
     * （实例 id 不同）各记一条，互不干扰。
     */
    private final Map<String, Map<String, ScriptPlayback>> scriptPlaybacks = new HashMap<>();

    private ScriptEventManager() {}

    public void addViewer(ServerPlayer player, String scriptId, String instanceId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        ScriptPlayback pb = byInstance != null ? byInstance.get(instanceId) : null;
        if (pb == null) {
            CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
            if (script == null) return;

            List<Clip> clips = extractEventClips(script);
            Integer scriptRatio = script.getMeta().getSkipVoteRatio();
            int voteRatio = scriptRatio != null ? scriptRatio : Config.skipVoteRatio;
            pb = new ScriptPlayback(scriptId, instanceId, clips, player.server.getTickCount(), voteRatio);
            scriptPlaybacks.computeIfAbsent(scriptId, k -> new HashMap<>()).put(instanceId, pb);
        }
        pb.viewers.add(player.getUUID());
    }

    public void startPlayback(ServerPlayer player, String scriptId, String instanceId) {
        addViewer(player, scriptId, instanceId);
    }

    /**
     * 账本精确解析：按 {@code (scriptId, instanceId)} 直取实例。
     * <p>
     * §3.7：结束 / 暂停信号随包携带实例 id（{@code C2SScriptFinishedPacket} /
     * {@code C2SScriptPausePacket}），与开始信号同一 id；无该实例时返回 {@code null}
     * （不再按“观看者成员”回退猜测）。
     */
    private ScriptPlayback playback(String scriptId, String instanceId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        return byInstance == null ? null : byInstance.get(instanceId == null ? "" : instanceId);
    }

    /** 摘除单个实例；脚本下不再有实例时移除脚本条目。 */
    private void removePlayback(ScriptPlayback pb) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(pb.scriptId);
        if (byInstance == null) return;
        byInstance.remove(pb.instanceId);
        if (byInstance.isEmpty()) scriptPlaybacks.remove(pb.scriptId);
    }

    public void onPlayerFinished(ServerPlayer player, String scriptId, String instanceId, CompletionReason reason) {
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) return;

        UUID uuid = player.getUUID();
        pb.viewers.remove(uuid);

        if (reason == CompletionReason.SKIPPED) {
            pb.skipVoters.add(uuid);
        }

        LOGGER.debug("Player {} finished script '{}' (instance '{}', viewers left: {})",
                player.getName().getString(), scriptId, pb.instanceId, pb.viewers.size());

        if (pb.viewers.isEmpty()) {
            removePlayback(pb);
            LOGGER.info("Script '{}' fully complete — all viewers finished", scriptId);
            return;
        }

        broadcastSkipVote(pb);

        if (reason == CompletionReason.SKIPPED) {
            int total = pb.viewers.size() + pb.skipVoters.size();
            int needed = Mth.ceil(total * pb.skipVoteRatio / 100f);
            if (pb.skipVoters.size() >= needed) {
                LOGGER.info("Script '{}' force-stopped by skip vote ({} / {} needed)", scriptId, pb.skipVoters.size(), needed);
                for (UUID remaining : pb.viewers) {
                    ServerPlayer p = player.server.getPlayerList().getPlayer(remaining);
                    if (p != null) S2CStopScriptPacket.send(p, scriptId, pb.instanceId, "");
                }
                removePlayback(pb);
            }
        }
    }

    private void broadcastSkipVote(ScriptPlayback pb) {
        if (pb.viewers.isEmpty()) return;
        float ratio = (float) pb.skipVoters.size() / pb.viewers.size() * 100f;
        int requiredRatio = pb.skipVoteRatio;
        boolean skip = ratio >= requiredRatio;
        if (!skip) {
            for (UUID vid : pb.viewers) {
                ServerPlayer vp = pb.server.getPlayerList().getPlayer(vid);
                if (vp != null) {
                    S2CSkipVoteUpdatePacket.send(vp, pb.scriptId, pb.skipVoters.size(), pb.viewers.size());
                }
            }
        }
    }

    public void onScriptFinished(ServerPlayer player, String scriptId, String instanceId, CompletionReason reason) {
        onPlayerFinished(player, scriptId, instanceId, reason);
    }

    public boolean isScriptActive(String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        return byInstance != null && !byInstance.isEmpty();
    }

    /**
     * 该玩家是否正在播放<b>指定脚本</b>（同脚本口径，§3.5）。
     * <p>
     * 只查 {@code scriptId} 名下的全部实例，任一实例把该玩家记为观看者即为真；
     * 因此它表达的是“同脚本同玩家单实例”的占用，<b>不</b>因“有别的脚本在播”而返回真。
     * 触发器门控（{@code TriggerEngine.shouldSkip}）据此实现跨脚本不阻塞。
     */
    public boolean isPlayerPlayingScript(UUID playerUuid, String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        if (byInstance == null) return false;
        for (ScriptPlayback pb : byInstance.values()) {
            if (pb.viewers.contains(playerUuid)) return true;
        }
        return false;
    }

    public boolean isFullyComplete(String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        if (byInstance == null) return true;
        for (ScriptPlayback pb : byInstance.values()) {
            if (!pb.viewers.isEmpty()) return false;
        }
        return true;
    }

    public int getRemainingViewers(String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        if (byInstance == null) return 0;
        int total = 0;
        for (ScriptPlayback pb : byInstance.values()) {
            total += pb.viewers.size();
        }
        return total;
    }

    public void onServerTick(MinecraftServer server) {
        // N1：ACK 超时重发检查（服务端侧；先于空检查执行）
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.tick();
        if (scriptPlaybacks.isEmpty()) return;
        int currentTick = server.getTickCount();

        for (Map<String, ScriptPlayback> byInstance : scriptPlaybacks.values()) {
            byInstance.entrySet().removeIf(entry -> {
                ScriptPlayback pb = entry.getValue();
                if (pb.viewers.isEmpty()) return true;

                pb.viewers.removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

                // 暂停态：不处理 keyframe，仅累计暂停 tick
                if (pb.paused) {
                    return false;
                }

                // 有效 elapsed = (当前tick - 开始tick - 总暂停tick) / 20
                float elapsed = (currentTick - pb.startTick - pb.totalPausedTicks) / 20f;

                int clipIndex = 0;
                for (Clip clip : pb.eventClips) {
                    float clipStart = clip.getStartTime();
                    float clipDuration = clip.getDuration();
                    float clipEnd = clipDuration < 0 ? Float.POSITIVE_INFINITY : clipStart + clipDuration;

                    if (elapsed < clipStart || elapsed > clipEnd) {
                        clipIndex++;
                        continue;
                    }

                    int kfIndex = 0;
                    for (Keyframe keyframe : clip.getKeyframes()) {
                        float globalTime = clipStart + keyframe.getTime();
                        int triggerKey = (clipIndex << 16) | kfIndex;

                        if (elapsed >= globalTime && !pb.triggeredKeyframes.contains(triggerKey)) {
                            String cmd = keyframe.getString("command", "");
                            if (!cmd.isEmpty()) {
                                for (UUID uuid : pb.viewers) {
                                    ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                                    if (p != null) executeCommand(p, cmd);
                                }
                            }
                            pb.triggeredKeyframes.add(triggerKey);
                        }
                        kfIndex++;
                    }
                    clipIndex++;
                }

                return false;
            });
        }
        scriptPlaybacks.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * 处理客户端发来的暂停/恢复信号。
     * <p>
     * 暂停时记录暂停起始 tick，恢复时累计暂停时长，
     * 使 onServerTick 中的 elapsed 计算跳过暂停时段。
     */
    public void handlePause(ServerPlayer player, String scriptId, String instanceId, boolean paused) {
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) return;

        if (paused && !pb.paused) {
            // 进入暂停
            pb.paused = true;
            pb.pauseStartTick = player.server.getTickCount();
            LOGGER.debug("Script '{}' paused at tick {} by player {}", scriptId, pb.pauseStartTick, player.getName().getString());
        } else if (!paused && pb.paused) {
            // 恢复
            int resumeTick = player.server.getTickCount();
            int pausedThisTime = resumeTick - pb.pauseStartTick;
            pb.totalPausedTicks += pausedThisTime;
            pb.paused = false;
            pb.pauseStartTick = -1;
            LOGGER.debug("Script '{}' resumed at tick {} (paused {} ticks)", scriptId, resumeTick, pausedThisTime);
        }
    }

    private void executeCommand(ServerPlayer player, String command) {
        String[] parts = command.split("\\s*&&\\s*");
        // 成功结果（/locate 坐标等）发给玩家，失败反馈吞掉（不打扰玩家，错误原因进 ErrorLog 排查）
        CommandSourceStack source = new com.immersivecinematics.immersive_cinematics.util.SuccessOnlySource(player);
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            try {
                player.server.getCommands().performPrefixedCommand(source, part.trim());
            } catch (Exception e) {
                LOGGER.error("Failed to execute event command for player {}: /{}", player.getName().getString(), part.trim(), e);
            }
        }
    }

    private List<Clip> extractEventClips(CinematicScript script) {
        return script.getTimeline().getTracks().stream()
                .filter(t -> t.getType() == TrackType.EVENT)
                .findFirst()
                .map(track -> track.getClips())
                .orElse(List.of());
    }

    public static class ScriptPlayback {
        final String scriptId;
        /** 播放实例 id（§3.7）；空串=无实例 id 的来源（该脚本的全部观看者共用一条退化账本） */
        final String instanceId;
        final Set<UUID> viewers;
        final Set<UUID> skipVoters;
        /** 本场播放实际生效的跳过投票比例（脚本覆盖 ?? 全局配置），创建时解析一次 */
        final int skipVoteRatio;
        final Set<Integer> triggeredKeyframes = new HashSet<>();
        final int startTick;
        final List<Clip> eventClips;
        MinecraftServer server;

        // ── 暂停状态 ──
        boolean paused = false;
        int pauseStartTick = -1;
        int totalPausedTicks = 0;

        ScriptPlayback(String scriptId, String instanceId, List<Clip> eventClips, int startTick, int skipVoteRatio) {
            this.scriptId = scriptId;
            this.instanceId = instanceId;
            this.viewers = new HashSet<>();
            this.skipVoters = new HashSet<>();
            this.skipVoteRatio = skipVoteRatio;
            this.eventClips = eventClips;
            this.startTick = startTick;
        }
    }
}
