package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collection;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CinematicCommand {

    private static final LevelResource WORLD_SCRIPT_DIR = new LevelResource("immersive_cinematics/scripts");
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";

    private static final SuggestionProvider<CommandSourceStack> SCRIPT_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        Path worldDir = server.getWorldPath(WORLD_SCRIPT_DIR);
        if (Files.isDirectory(globalDir)) {
            try (Stream<Path> files = Files.list(globalDir)) {
                files.filter(p -> p.toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replace(".json", ""))
                        .forEach(builder::suggest);
            } catch (IOException ignored) {}
        }
        if (Files.isDirectory(worldDir)) {
            try (Stream<Path> files = Files.list(worldDir)) {
                files.filter(p -> p.toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replace(".json", ""))
                        .forEach(builder::suggest);
            } catch (IOException ignored) {}
        }
        return SharedSuggestionProvider.suggest(new String[0], builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("icinematics")
                .then(Commands.literal("play")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests(SCRIPT_SUGGESTIONS)
                                .executes(CinematicCommand::playScript)
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(CinematicCommand::playScript))))
                .then(Commands.literal("stop")
                        .executes(CinematicCommand::stopScript)
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(CinematicCommand::stopScript)))
                .then(Commands.literal("status")
                        .executes(CinematicCommand::showStatus))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(CinematicCommand::reloadScripts))
        );
    }

    private static int playScript(CommandContext<CommandSourceStack> context) {
        String filePath = StringArgumentType.getString(context, "file");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

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

        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "players");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            targets = server.getPlayerList().getPlayers();
        }

        for (ServerPlayer player : targets) {
            S2CPlayScriptPacket.send(player, json);
        }

        final int count = targets.size();
        source.sendSuccess(() -> Component.literal(
                "§a已向 " + count + " 名玩家推送脚本: §f" + script.getMeta().getName() +
                " §7(总时长: " + String.format("%.1f", script.getTimeline().getTotalDuration()) + "s)"), false);
        return 1;
    }

    private static int stopScript(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "players");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            targets = server.getPlayerList().getPlayers();
        }

        for (ServerPlayer player : targets) {
            S2CStopScriptPacket.send(player, "");
        }

        final int count = targets.size();
        source.sendSuccess(() -> Component.literal(
                "§a已向 " + count + " 名玩家发送停止指令"), false);
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

    private static int reloadScripts(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        Path worldDir = server.getWorldPath(WORLD_SCRIPT_DIR);

        if (!Files.isDirectory(globalDir)) {
            source.sendFailure(Component.literal("§c全局脚本目录不存在: " + globalDir));
            return 0;
        }

        try {
            Files.createDirectories(worldDir);
            try (Stream<Path> files = Files.list(globalDir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(globalFile -> {
                    Path target = worldDir.resolve(globalFile.getFileName());
                    try {
                        Files.copy(globalFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        source.sendSuccess(() -> Component.literal("§7 同步: " + globalFile.getFileName()), false);
                    } catch (IOException e) {
                        source.sendFailure(Component.literal("§c同步失败 " + globalFile.getFileName() + ": " + e.getMessage()));
                    }
                });
            }
            ScriptManager.INSTANCE.reload(server);
            source.sendSuccess(() -> Component.literal("§a脚本重载完成，共 " + ScriptManager.INSTANCE.getAllScripts().size() + " 个脚本生效"), false);
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c重载失败: " + e.getMessage()));
            return 0;
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
