package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CinematicCommand {

    private static final LevelResource WORLD_SCRIPT_DIR = new LevelResource("immersive_cinematics/scripts");
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("icinematics")
                .then(Commands.literal("play")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(CinematicCommand::playScript)))
                .then(Commands.literal("stop")
                        .executes(CinematicCommand::stopScript))
                .then(Commands.literal("status")
                        .executes(CinematicCommand::showStatus))
        );
    }

    private static int playScript(CommandContext<CommandSourceStack> context) {
        String filePath = StringArgumentType.getString(context, "file");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        // 搜索路径：全局目录 → 世界目录 → 绝对路径
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        Path worldDir = server.getWorldPath(WORLD_SCRIPT_DIR);

        Path scriptPath = findScriptFile(filePath, globalDir, worldDir);
        if (scriptPath == null) {
            source.sendFailure(Component.literal("§c脚本文件不存在: " + filePath +
                    "\n§7搜索路径:" +
                    "\n§7  1. " + globalDir.resolve(filePath) +
                    "\n§7  2. " + globalDir.resolve(filePath + ".json") +
                    "\n§7  3. " + worldDir.resolve(filePath) +
                    "\n§7  4. " + worldDir.resolve(filePath + ".json") +
                    "\n§7请将 .json 脚本文件放入: " + globalDir));
            return 0;
        }

        String json;
        try {
            json = Files.readString(scriptPath);
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c读取脚本文件失败: " + e.getMessage()));
            return 0;
        }

        CinematicScript script;
        try {
            script = ScriptParser.parse(json);
        } catch (ScriptParseException e) {
            source.sendFailure(Component.literal("§c脚本解析错误: " + e.getMessage()));
            return 0;
        }

        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            int result = CameraManager.INSTANCE.playScript(script);
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                if (result == 2) {
                    player.displayClientMessage(
                            Component.literal("§e脚本已排队: §f" + script.getName() +
                                    " §7(将在当前脚本结束后自动播放)"), false);
                } else if (result == 1) {
                    player.displayClientMessage(
                            Component.literal("§a脚本播放开始: §f" + script.getName() +
                                    " §7(总时长: " + script.getTotalDuration() + "s)"), false);
                } else {
                    player.displayClientMessage(
                            Component.literal("§c脚本播放被拒绝（当前脚本不可打断）"), false);
                }
            }
        });

        source.sendSuccess(() -> Component.literal("§7脚本加载成功，正在调度播放..."), false);
        return 1;
    }

    private static int stopScript(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            boolean wasPlaying = CameraManager.INSTANCE.isActive();
            CameraManager.INSTANCE.requestExit(ExitReason.SYSTEM_STOP);
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                if (wasPlaying) {
                    player.displayClientMessage(
                            Component.literal("§a脚本播放已停止"), false);
                } else {
                    player.displayClientMessage(
                            Component.literal("§7当前没有正在播放的脚本"), false);
                }
            }
        });

        source.sendSuccess(() -> Component.literal("§7正在调度停止脚本..."), false);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        CameraManager mgr = CameraManager.INSTANCE;
        if (!mgr.isActive()) {
            source.sendSuccess(() -> Component.literal("§7相机未激活 §8| 全局脚本目录: " + globalDir), false);
            return 0;
        }

        if (mgr.isScriptMode()) {
            CinematicScript script = mgr.getScriptPlayer().getScript();
            String name = script != null ? script.getName() : "未知";
            float remaining = mgr.getScriptPlayer().getRemainingTime();
            source.sendSuccess(() -> Component.literal("§e脚本模式: §f" + name +
                    " §7(剩余: " + String.format("%.1f", remaining) + "s)"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§e测试模式 §7(P键激活)"), false);
        }

        return 1;
    }

    private static Path findScriptFile(String filePath, Path globalDir, Path worldDir) {
        Path candidate;

        // 1. 全局目录
        candidate = globalDir.resolve(filePath);
        if (Files.exists(candidate)) return candidate;
        if (!filePath.endsWith(".json")) {
            candidate = globalDir.resolve(filePath + ".json");
            if (Files.exists(candidate)) return candidate;
        }

        // 2. 世界目录
        candidate = worldDir.resolve(filePath);
        if (Files.exists(candidate)) return candidate;
        if (!filePath.endsWith(".json")) {
            candidate = worldDir.resolve(filePath + ".json");
            if (Files.exists(candidate)) return candidate;
        }

        // 3. 绝对路径
        candidate = Path.of(filePath);
        if (Files.exists(candidate)) return candidate;

        return null;
    }
}
