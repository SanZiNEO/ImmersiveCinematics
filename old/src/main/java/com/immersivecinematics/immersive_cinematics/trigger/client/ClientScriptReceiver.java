package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPlaybackStartedPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CSkipVoteUpdatePacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public class ClientScriptReceiver {

    private static final Logger LOGGER = LogUtils.getLogger();

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
                NetworkHandler.sendToServer(new C2SPlaybackStartedPacket(script.getId()));
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
        });
    }
}
