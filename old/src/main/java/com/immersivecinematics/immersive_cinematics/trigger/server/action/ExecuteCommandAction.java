package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import com.google.gson.JsonObject;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public class ExecuteCommandAction implements TriggerAction {

    private final String command;

    public ExecuteCommandAction(String command) {
        this.command = command;
    }

    public static ExecuteCommandAction fromJson(JsonObject obj) {
        return new ExecuteCommandAction(obj.get("command").getAsString());
    }

    @Override
    public void execute(ServerPlayer player) {
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(4)
                .withSuppressedOutput();
        player.server.getCommands().performPrefixedCommand(source, command);
    }
}
