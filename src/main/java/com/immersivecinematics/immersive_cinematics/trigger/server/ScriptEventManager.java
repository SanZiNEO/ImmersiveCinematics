package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.EventClip;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ScriptEventManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ScriptEventManager INSTANCE = new ScriptEventManager();

    private final Map<UUID, ActivePlayback> activePlaybacks = new HashMap<>();

    private ScriptEventManager() {}

    public void startPlayback(ServerPlayer player, CinematicScript script) {
        ActivePlayback playback = new ActivePlayback(
                script.getId(),
                player.server.getTickCount(),
                extractEventClips(script)
        );
        activePlaybacks.put(player.getUUID(), playback);
    }

    public void stopPlayback(UUID playerUuid, String scriptId) {
        ActivePlayback removed = activePlaybacks.remove(playerUuid);
        if (removed != null) {
            LOGGER.debug("Stopped event playback for {} script={}", playerUuid, scriptId);
        }
    }

    public void onServerTick(MinecraftServer server) {
        if (activePlaybacks.isEmpty()) return;
        int currentTick = server.getTickCount();

        activePlaybacks.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;

            ActivePlayback playback = entry.getValue();
            float elapsed = playback.getElapsedSeconds(currentTick);

            while (playback.nextClipIndex < playback.eventClips.size()) {
                EventClip clip = playback.eventClips.get(playback.nextClipIndex);
                if (clip.getStartTime() <= elapsed) {
                    executeCommand(player, clip.getCommand());
                    playback.nextClipIndex++;
                } else {
                    break;
                }
            }
            return false;
        });
    }

    public void onScriptFinished(ServerPlayer player, String scriptId, CompletionReason reason) {
        stopPlayback(player.getUUID(), scriptId);
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

    private List<EventClip> extractEventClips(CinematicScript script) {
        return script.getTimeline().getTracks().stream()
                .filter(t -> t.getType() == TrackType.EVENT)
                .findFirst()
                .map(TimelineTrack::getEventClips)
                .orElse(List.of());
    }

    public static class ActivePlayback {
        final String scriptId;
        final long startTick;
        final List<EventClip> eventClips;
        int nextClipIndex;

        ActivePlayback(String scriptId, long startTick, List<EventClip> eventClips) {
            this.scriptId = scriptId;
            this.startTick = startTick;
            this.eventClips = eventClips;
            this.nextClipIndex = 0;
        }

        public float getElapsedSeconds(int currentTick) {
            return (currentTick - startTick) / 20.0f;
        }
    }
}
