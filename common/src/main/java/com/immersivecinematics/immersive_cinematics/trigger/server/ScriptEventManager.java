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

                // 暂停态：不处理 keyframe（恢复帧按上报内容位置重定基准）
                if (pb.paused) {
                    return false;
                }

                // 时间线读数 = 上报的内容位置 + 基准之后经过的服务端 tick
                // （未收到上报 = 基准不动，回落 tick 推算——不卡事件）
                float elapsed = pb.timelineAnchorSeconds + (currentTick - pb.timelineAnchorTick) / 20f;

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
     * 处理客户端发来的暂停/恢复信号（0.3.6 C-pause：载荷带内容相对位置）。
     * <p>
     * 状态转换那一帧按上报位置重定 EVENT 时间线基准（{@link ScriptPlayback#timelineAnchorSeconds}）：
     * 暂停期间不处理关键帧，恢复后从上报位置继续推进（退掉墙钟暂停 tick 账本）。
     *
     * @param positionClipStart 片段起始秒（{@link Float#NaN} = 无片段覆盖：位置即 {@code positionOffset} 绝对秒）
     * @param positionOffset    片段内偏移秒（{@link Float#NaN} = 未上报 → 保持既有基准不动，回落 tick 推算）
     */
    public void handlePause(ServerPlayer player, String scriptId, String instanceId, boolean paused,
                            float positionClipStart, float positionOffset) {
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) return;

        int tick = player.server.getTickCount();
        if (paused && !pb.paused) {
            // 进入暂停
            pb.paused = true;
            reanchor(pb, positionClipStart, positionOffset, tick);
            LOGGER.debug("Script '{}' paused at tick {} (position {}) by player {}",
                    scriptId, tick, pb.timelineAnchorSeconds, player.getName().getString());
        } else if (!paused && pb.paused) {
            // 恢复：从上报的内容位置续
            pb.paused = false;
            reanchor(pb, positionClipStart, positionOffset, tick);
            LOGGER.debug("Script '{}' resumed at tick {} (position {})",
                    scriptId, tick, pb.timelineAnchorSeconds);
        }
    }

    /** 按上报的内容位置重定时间线基准；未上报（NaN）时保持基准不动（回落 tick 推算，不卡事件）。 */
    private static void reanchor(ScriptPlayback pb, float positionClipStart, float positionOffset, int tick) {
        if (Float.isNaN(positionOffset)) return;
        float position = Float.isNaN(positionClipStart) ? positionOffset : positionClipStart + positionOffset;
        if (!Float.isFinite(position) || position < 0f) return;
        pb.timelineAnchorSeconds = position;
        pb.timelineAnchorTick = tick;
    }

    /**
     * 规则动作 {@code emit_command} 的服务端入口（0.3.6 C6-3）：执行口径与 EVENT 轨完全一致
     * （{@code &&} 拆分 + {@link SuccessOnlySource} 成功结果回执，见 {@link #executeCommand}）。
     *
     * <p><b>权限门</b>：只有该脚本<b>当前播放实例的观看者</b>才执行——命令随客户端规则触发而来，
     * 不校验就等于把「任意命令执行」开放给任何客户端包；非观看者 / 无该实例 / 空命令一律丢弃。</p>
     */
    public void handleRuleCommand(ServerPlayer player, String scriptId, String instanceId, String command) {
        if (!isRuleCommandAllowed(player != null ? player.getUUID() : null, scriptId, instanceId)) {
            LOGGER.debug("规则命令被拒（非该脚本观看者或无此实例）: script={} instance={}",
                    scriptId, instanceId);
            return;
        }
        if (command == null || command.isBlank()) return;
        executeCommand(player, command);
    }

    /**
     * 规则命令权限门：发送者是否是该脚本当前播放实例的观看者（{@code ScriptPlayback.viewers} 含其 UUID）。
     * 探针可直调（不需要 {@code ServerPlayer} 实例）。
     */
    public boolean isRuleCommandAllowed(UUID playerId, String scriptId, String instanceId) {
        if (playerId == null || scriptId == null) return false;
        ScriptPlayback pb = playback(scriptId, instanceId);
        return pb != null && pb.viewers.contains(playerId);
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

        // ── 暂停状态与时间线基准 ──
        /** 本实例暂停中（暂停期间不处理 EVENT 关键帧）。 */
        boolean paused = false;
        /** EVENT 时间线基准：最近一次上报的内容位置（秒；初始 = 脚本起点 0）。 */
        float timelineAnchorSeconds = 0f;
        /** 基准建立时的服务端 tick（读数 = 基准 + 其后经过的 tick）。 */
        int timelineAnchorTick;

        ScriptPlayback(String scriptId, String instanceId, List<Clip> eventClips, int startTick, int skipVoteRatio) {
            this.scriptId = scriptId;
            this.instanceId = instanceId;
            this.viewers = new HashSet<>();
            this.skipVoters = new HashSet<>();
            this.skipVoteRatio = skipVoteRatio;
            this.eventClips = eventClips;
            this.startTick = startTick;
            this.timelineAnchorTick = startTick;
        }
    }
}
