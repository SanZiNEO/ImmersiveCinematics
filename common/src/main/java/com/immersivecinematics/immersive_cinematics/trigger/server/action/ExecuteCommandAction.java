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
        String[] parts = command.split("\\s*&&\\s*");
        // 成功结果（/locate 坐标等）发给玩家，失败反馈吞掉（不打扰玩家，错误原因进 ErrorLog 排查）
        CommandSourceStack source = new com.immersivecinematics.immersive_cinematics.util.SuccessOnlySource(player);
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            try {
                player.server.getCommands().performPrefixedCommand(source, part.trim());
            } catch (Exception e) {
                com.mojang.logging.LogUtils.getLogger().error("Failed to execute trigger command for player {}: /{}",
                        player.getName().getString(), part.trim(), e);
            }
        }
    }
}
