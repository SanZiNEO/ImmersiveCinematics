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

    private final Map<String, ScriptPlayback> scriptPlaybacks = new HashMap<>();

    private ScriptEventManager() {}

    public void addViewer(ServerPlayer player, String scriptId) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        if (pb == null) {
            CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
            if (script == null) return;

            List<Clip> clips = extractEventClips(script);
            pb = new ScriptPlayback(scriptId, clips, player.server.getTickCount());
            scriptPlaybacks.put(scriptId, pb);
        }
        pb.viewers.add(player.getUUID());
    }

    public void startPlayback(ServerPlayer player, String scriptId) {
        addViewer(player, scriptId);
    }

    public void onPlayerFinished(ServerPlayer player, String scriptId, CompletionReason reason) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        if (pb == null) return;

        UUID uuid = player.getUUID();
        pb.viewers.remove(uuid);

        if (reason == CompletionReason.SKIPPED) {
            pb.skipVoters.add(uuid);
        }

        LOGGER.debug("Player {} finished script '{}' (viewers left: {})",
                player.getName().getString(), scriptId, pb.viewers.size());

        if (pb.viewers.isEmpty()) {
            scriptPlaybacks.remove(scriptId);
            LOGGER.info("Script '{}' fully complete — all viewers finished", scriptId);
            return;
        }

        broadcastSkipVote(pb);

        if (reason == CompletionReason.SKIPPED) {
            int total = pb.viewers.size() + pb.skipVoters.size();
            int needed = Mth.ceil(total * Config.skipVoteRatio / 100f);
            if (pb.skipVoters.size() >= needed) {
                LOGGER.info("Script '{}' force-stopped by skip vote ({} / {} needed)", scriptId, pb.skipVoters.size(), needed);
                for (UUID remaining : pb.viewers) {
                    ServerPlayer p = player.server.getPlayerList().getPlayer(remaining);
                    if (p != null) new S2CStopScriptPacket(scriptId).sendTo(p);
                }
                scriptPlaybacks.remove(scriptId);
            }
        }
    }

    private void broadcastSkipVote(ScriptPlayback pb) {
        if (pb.viewers.isEmpty()) return;
        float ratio = (float) pb.skipVoters.size() / pb.viewers.size() * 100f;
        int requiredRatio = Config.skipVoteRatio;
        boolean skip = ratio >= requiredRatio;
        if (!skip) {
            for (UUID vid : pb.viewers) {
                ServerPlayer vp = pb.server.getPlayerList().getPlayer(vid);
                if (vp != null) {
                    new S2CSkipVoteUpdatePacket(pb.scriptId, pb.skipVoters.size(), pb.viewers.size())
                            .sendTo(vp);
                }
            }
        }
    }

    public void stopPlayback(UUID playerUuid, String scriptId) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        if (pb == null) return;
        pb.viewers.remove(playerUuid);
        pb.skipVoters.remove(playerUuid);
        if (pb.viewers.isEmpty()) {
            scriptPlaybacks.remove(scriptId);
        }
    }

    public void onScriptFinished(ServerPlayer player, String scriptId, CompletionReason reason) {
        onPlayerFinished(player, scriptId, reason);
    }

    public boolean isScriptActive(String scriptId) {
        return scriptPlaybacks.containsKey(scriptId);
    }

    public boolean isPlayerPlayingScript(UUID playerUuid, String scriptId) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        return pb != null && pb.viewers.contains(playerUuid);
    }

    public boolean isFullyComplete(String scriptId) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        return pb == null || pb.viewers.isEmpty();
    }

    public int getRemainingViewers(String scriptId) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        return pb == null ? 0 : pb.viewers.size();
    }

    public void onServerTick(MinecraftServer server) {
        // N1：ACK 超时重发检查（服务端侧；先于空检查执行）
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.tick();
        if (scriptPlaybacks.isEmpty()) return;
        int currentTick = server.getTickCount();

        scriptPlaybacks.entrySet().removeIf(entry -> {
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

    /**
     * 处理客户端发来的暂停/恢复信号。
     * <p>
     * 暂停时记录暂停起始 tick，恢复时累计暂停时长，
     * 使 onServerTick 中的 elapsed 计算跳过暂停时段。
     */
    public void handlePause(ServerPlayer player, String scriptId, boolean paused) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
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
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(4)
                .withSuppressedOutput();
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
        final Set<UUID> viewers;
        final Set<UUID> skipVoters;
        final Set<Integer> triggeredKeyframes = new HashSet<>();
        final int startTick;
        final List<Clip> eventClips;
        MinecraftServer server;

        // ── 暂停状态 ──
        boolean paused = false;
        int pauseStartTick = -1;
        int totalPausedTicks = 0;

        ScriptPlayback(String scriptId, List<Clip> eventClips, int startTick) {
            this.scriptId = scriptId;
            this.viewers = new HashSet<>();
            this.skipVoters = new HashSet<>();
            this.eventClips = eventClips;
            this.startTick = startTick;
        }
    }
}
