package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /cinematic 命令 — 加载并播放脚本
 * <p>
 * 用法：
 * <ul>
 *   <li>/cinematic play <filepath> — 从文件加载并播放脚本</li>
 *   <li>/cinematic stop — 停止当前播放</li>
 *   <li>/cinematic status — 显示当前播放状态</li>
 * </ul>
 * <p>
 * 文件搜索路径（按优先级）：
 * <ol>
 *   <li>绝对路径</li>
 *   <li>游戏目录下（.minecraft/ 或 run/）</li>
 *   <li>游戏目录/cinematics/ 子目录</li>
 *   <li>自动添加 .json 后缀再搜索</li>
 * </ol>
 * 首次使用时自动创建 cinematics/ 目录。
 */
public class CinematicCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cinematic")
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

        Path gameDir = source.getServer().getServerDirectory().toPath().toAbsolutePath();

        // 自动创建 cinematics/ 目录
        Path cinematicsDir = gameDir.resolve("cinematics");
        if (!Files.exists(cinematicsDir)) {
            try {
                Files.createDirectories(cinematicsDir);
                source.sendSuccess(() -> Component.literal("§7已创建脚本目录: " + cinematicsDir), false);
            } catch (IOException e) {
                source.sendFailure(Component.literal("§c无法创建脚本目录: " + cinematicsDir + " — " + e.getMessage()));
            }
        }

        // 读取文件：按优先级搜索
        Path scriptPath = findScriptFile(filePath, gameDir);

        if (scriptPath == null) {
            source.sendFailure(Component.literal("§c脚本文件不存在: " + filePath +
                    "\n§7搜索路径:" +
                    "\n§7  1. 绝对路径" +
                    "\n§7  2. " + gameDir.resolve(filePath) +
                    "\n§7  3. " + cinematicsDir.resolve(filePath) +
                    "\n§7  4. " + cinematicsDir.resolve(filePath + ".json") +
                    "\n§7请将 .json 脚本文件放入: " + cinematicsDir));
            return 0;
        }

        String json;
        try {
            json = Files.readString(scriptPath);
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c读取脚本文件失败: " + e.getMessage()));
            return 0;
        }

        // 解析脚本
        CinematicScript script;
        try {
            script = ScriptParser.parse(json);
        } catch (ScriptParseException e) {
            source.sendFailure(Component.literal("§c脚本解析错误: " + e.getMessage()));
            return 0;
        }

        // 在客户端线程执行播放（命令在服务端线程执行）
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            CameraManager.INSTANCE.playScript(script);
        });

        source.sendSuccess(() -> Component.literal("§a脚本播放开始: §f" + script.getName() +
                " §7(总时长: " + script.getTotalDuration() + "s)"), false);
        return 1;
    }

    private static int stopScript(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            CameraManager.INSTANCE.stopScript();
        });

        source.sendSuccess(() -> Component.literal("§a脚本播放已停止"), false);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CameraManager mgr = CameraManager.INSTANCE;

        if (!mgr.isActive()) {
            // 显示脚本目录位置
            Path gameDir = source.getServer().getServerDirectory().toPath().toAbsolutePath();
            Path cinematicsDir = gameDir.resolve("cinematics");
            source.sendSuccess(() -> Component.literal("§7相机未激活 §8| 脚本目录: " + cinematicsDir), false);
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

    /**
     * 按优先级搜索脚本文件
     *
     * @return 找到的文件路径，或 null
     */
    private static Path findScriptFile(String filePath, Path gameDir) {
        Path cinematicsDir = gameDir.resolve("cinematics");

        // 1. 绝对路径
        Path candidate = Path.of(filePath);
        if (Files.exists(candidate)) return candidate;

        // 2. 游戏目录相对路径
        candidate = gameDir.resolve(filePath);
        if (Files.exists(candidate)) return candidate;

        // 3. cinematics/ 子目录
        candidate = cinematicsDir.resolve(filePath);
        if (Files.exists(candidate)) return candidate;

        // 4. 自动添加 .json 后缀
        if (!filePath.endsWith(".json")) {
            candidate = cinematicsDir.resolve(filePath + ".json");
            if (Files.exists(candidate)) return candidate;
        }

        return null;
    }
}
