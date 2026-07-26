package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
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
        pb.finishedViewers.add(uuid);

        if (reason == CompletionReason.SKIPPED) {
            pb.skipVoters.add(uuid);
        }

        LOGGER.debug("Player {} finished script '{}' (viewers left: {}, skip voters: {})",
                player.getName().getString(), scriptId, pb.viewers.size(), pb.skipVoters.size());

        if (pb.viewers.isEmpty()) {
            scriptPlaybacks.remove(scriptId);
            LOGGER.info("Script '{}' fully complete — all {} viewer(s) finished", scriptId, pb.finishedViewers.size());
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
        pb.finishedViewers.remove(playerUuid);
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
        return pb != null && pb.viewers.size() == pb.finishedViewers.size();
    }

    public int getRemainingViewers(String scriptId) {
        ScriptPlayback pb = scriptPlaybacks.get(scriptId);
        return pb == null ? 0 : pb.viewers.size() - pb.finishedViewers.size();
    }

    public void onServerTick(MinecraftServer server) {
        if (scriptPlaybacks.isEmpty()) return;
        int currentTick = server.getTickCount();

        scriptPlaybacks.entrySet().removeIf(entry -> {
            ScriptPlayback pb = entry.getValue();
            if (pb.viewers.isEmpty()) return true;

            pb.viewers.removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

            int elapsed = currentTick - pb.startTick;
            while (pb.nextClipIndex < pb.eventClips.size()) {
                Clip clip = pb.eventClips.get(pb.nextClipIndex);
                if (clip.getStartTime() <= elapsed / 20f) {
                    for (UUID uuid : pb.finishedViewers) {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p != null) executeCommand(p, clip.getCommand());
                    }
                    pb.nextClipIndex++;
                } else {
                    break;
                }
            }

            if (pb.nextClipIndex >= pb.eventClips.size()) {
                pb.finishedViewers.clear();
            }

            return false;
        });
    }

    private void executeCommand(ServerPlayer player, String command) {
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(4)
                .withSuppressedOutput();
        try {
            player.server.getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            LOGGER.error("Failed to execute event command for player {}: /{}", player.getName().getString(), command, e);
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
        final Set<UUID> finishedViewers;
        final Set<UUID> skipVoters;
        final int startTick;
        final List<Clip> eventClips;
        int nextClipIndex;
        MinecraftServer server;

        ScriptPlayback(String scriptId, List<Clip> eventClips, int startTick) {
            this.scriptId = scriptId;
            this.viewers = new HashSet<>();
            this.finishedViewers = new HashSet<>();
            this.skipVoters = new HashSet<>();
            this.eventClips = eventClips;
            this.startTick = startTick;
            this.nextClipIndex = 0;
        }
    }
}
