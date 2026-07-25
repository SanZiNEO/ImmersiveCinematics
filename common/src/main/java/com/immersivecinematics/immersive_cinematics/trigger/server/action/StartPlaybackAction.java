package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

public class StartPlaybackAction implements TriggerAction {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String scriptId;

    public StartPlaybackAction(String scriptId) {
        this.scriptId = scriptId;
    }

    public static StartPlaybackAction fromJson(JsonObject obj) {
        return new StartPlaybackAction(obj.get("script_id").getAsString());
    }

    @Override
    public void execute(ServerPlayer player) {
        CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
        if (script == null || script.getRawJson() == null) {
            LOGGER.warn("Cannot play script '{}': script={} rawJson={}", scriptId, script, script != null ? "present" : "null");
            return;
        }
        LOGGER.info("Sending play packet for script '{}' to player {}", scriptId, player.getName().getString());
        S2CPlayScriptPacket.send(player, script.getRawJson());
    }
}
