package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import net.minecraft.server.level.ServerPlayer;

public class StopPlaybackAction implements TriggerAction {

    private final String scriptId;

    public StopPlaybackAction(String scriptId) {
        this.scriptId = scriptId;
    }

    public static StopPlaybackAction fromJson(JsonObject obj) {
        String id = obj.has("script_id") ? obj.get("script_id").getAsString() : "";
        return new StopPlaybackAction(id);
    }

    @Override
    public void execute(ServerPlayer player) {
        S2CStopScriptPacket.send(player, scriptId);
    }
}
