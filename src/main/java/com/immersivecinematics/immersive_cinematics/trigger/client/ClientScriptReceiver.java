package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SPlaybackStartedPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public class ClientScriptReceiver {

    private static final Logger LOGGER = LogUtils.getLogger();

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
