package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CinematicCommand {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/Command");
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";

    /** Tab 补全递归深度（与 ScriptManager 保持一致：子文件夹组织） */
    private static final int MAX_SCRIPT_DEPTH = 5;

    private static final SuggestionProvider<CommandSourceStack> SCRIPT_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        if (Files.isDirectory(globalDir)) {
            try (Stream<Path> files = Files.walk(globalDir, MAX_SCRIPT_DEPTH)) {
                files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                        .map(p -> toCommandId(globalDir, p))
                        .sorted()
                        .forEach(builder::suggest);
            } catch (IOException e) {
                // Tab 补全是 best-effort：目录不可读时无建议即可，但这是真实问题，报 WARN 可见
                LOGGER.warn("Tab 补全扫描脚本目录失败: {} ({})", e.getMessage(), globalDir);
            }
        }
        return SharedSuggestionProvider.suggest(new String[0], builder);
    };

    /** 把 globalDir 下的文件转为命令 ID：目录中的 / 用 _ 代替，目录与文件名用 : 分隔 */
    private static String toCommandId(Path globalDir, Path p) {
        String rel = toForwardRel(globalDir, p).replace(".json", "");
        int idx = rel.lastIndexOf('/');
        if (idx < 0) return rel;
        return rel.substring(0, idx).replace('/', '_') + ":" + rel.substring(idx + 1);
    }

    /** 把 globalDir 下的文件转为向前斜杠的相对路径 */
    private static String toForwardRel(Path globalDir, Path p) {
        return globalDir.relativize(p).toString().replace('\\', '/');
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("icinematics")
                .then(Commands.literal("play")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("file", ResourceLocationArgument.id())
                                .suggests(SCRIPT_SUGGESTIONS)
                                .executes(CinematicCommand::playScript)
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(CinematicCommand::playScript))))
                .then(Commands.literal("stop")
                        .executes(CinematicCommand::stopScript)
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(CinematicCommand::stopScript)))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(CinematicCommand::reloadScripts))
                .then(Commands.literal("validate")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("file", ResourceLocationArgument.id())
                                .suggests(SCRIPT_SUGGESTIONS)
                                .executes(CinematicCommand::validateScriptFile)))
        );
    }

    private static int playScript(CommandContext<CommandSourceStack> context) {
        ResourceLocation location = ResourceLocationArgument.getId(context, "file");
        String filePath = scriptPathOf(location);
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        Path scriptPath = findScriptFile(filePath, globalDir);
        if (scriptPath == null) {
            source.sendFailure(Component.literal("§c脚本文件不存在: " + filePath +
                    "\n§7搜索路径:" +
                    "\n§7  1. " + globalDir.resolve(filePath) +
                    "\n§7  2. " + globalDir.resolve(filePath + ".json") +
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
        } catch (IllegalArgumentException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // 未指定 players 或选择器解析失败 → 回退全体在线玩家（play 的默认目标语义）
            targets = server.getPlayerList().getPlayers();
        }

        // N1：握手 — 登记 ACK，超时重发（幂等：playCinematic 有打断/排队逻辑）
        final Collection<ServerPlayer> ackTargets = targets;
        String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
        // 结构/方块来源解析：脚本关键帧中的 look_at_target_structure、position.relative_origin 等
        // → 服务端定位最近结构/方块 → 替换为坐标（坐标按执行者所在维度/位置定位；脚本文件本身不被修改）
        final String resolvedJson = com.immersivecinematics.immersive_cinematics.util.ScriptStructureResolver
                .resolveTargets(json, source.getLevel(), source.getPosition());
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.expect(refId, () -> {
            for (ServerPlayer p : ackTargets) {
                S2CPlayScriptPacket.send(p, resolvedJson, refId);
            }
        });
        for (ServerPlayer player : targets) {
            S2CPlayScriptPacket.send(player, resolvedJson, refId);
        }

        final int count = targets.size();
        LOGGER.info("已向 {} 名玩家推送脚本: {} (总时长: {}s)",
                count, script.getMeta().getName(),
                String.format("%.1f", script.getTimeline().getTotalDuration()));
        return 1;
    }

    private static int stopScript(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "players");
        } catch (IllegalArgumentException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // 未指定 players 或选择器解析失败 → 回退全体在线玩家（stop 的默认目标语义）
            targets = server.getPlayerList().getPlayers();
        }

        // N1：握手 — 登记 ACK，超时重发（幂等：重复 stop 安全）
        final Collection<ServerPlayer> ackTargets = targets;
        String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.expect(refId, () -> {
            for (ServerPlayer p : ackTargets) {
                S2CStopScriptPacket.send(p, "", refId);
            }
        });
        for (ServerPlayer player : targets) {
            S2CStopScriptPacket.send(player, "", refId);
        }

        final int count = targets.size();
        LOGGER.info("已向 {} 名玩家发送停止指令", count);
        return 1;
    }

    private static int reloadScripts(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        // 脚本统一从游戏根目录加载，reload = 重新加载持有的脚本（不做任何文件同步）
        try {
            Files.createDirectories(globalDir);
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c创建脚本目录失败: " + e.getMessage()));
            return 0;
        }
        ScriptManager.INSTANCE.reload(server);
        LOGGER.info("脚本重载完成，共 {} 个脚本生效", ScriptManager.INSTANCE.getAllScripts().size());
        return 1;
    }

    /**
     * /icinematics validate <file> — 脚本静态校验（AI 写脚本后的自查闭环）：
     * 输出完整问题清单（结构错误/字段缺失/语义错误/缺省字段提示），一次给全。
     */
    private static int validateScriptFile(CommandContext<CommandSourceStack> context) {
        ResourceLocation location = ResourceLocationArgument.getId(context, "file");
        String filePath = scriptPathOf(location);
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        Path scriptPath = findScriptFile(filePath, globalDir);
        if (scriptPath == null) {
            source.sendFailure(Component.literal("§c脚本文件不存在: " + filePath
                    + "\n§7请将 .json 脚本文件放入: " + globalDir));
            return 0;
        }

        String json;
        try {
            json = Files.readString(scriptPath);
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c读取脚本文件失败: " + e.getMessage()));
            return 0;
        }

        // 附带已加载脚本 id：validator 可跨脚本检查 requires 是否指向不存在的脚本
        java.util.Set<String> knownIds = ScriptManager.INSTANCE.getAllScripts().stream()
                .map(CinematicScript::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> issues = com.immersivecinematics.immersive_cinematics.script.ScriptValidator.validate(json, knownIds);
        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§a校验通过: " + scriptPath.getFileName()), false);
            return 1;
        }

        StringBuilder msg = new StringBuilder("§c发现 " + issues.size() + " 个问题:\n");
        for (String issue : issues) {
            msg.append("§7  - ").append(issue).append("\n");
        }
        source.sendSuccess(() -> Component.literal(msg.toString()), false);
        return 1;
    }

    /**
     * 命令 ID → 相对文件路径：
     * <ul>
     *   <li>根目录脚本：直接返回文件名（ResourceLocation 的默认 namespace 为 minecraft）；</li>
     *   <li>子目录脚本：namespace 中的下划线还原为目录斜杠，再拼接文件名。</li>
     * </ul>
     */
    private static String scriptPathOf(ResourceLocation location) {
        String ns = location.getNamespace();
        String path = location.getPath();
        if ("minecraft".equals(ns)) return path;
        return ns.replace('_', '/') + "/" + path;
    }

    private static Path findScriptFile(String filePath, Path scriptDir) {
        Path candidate;

        // 只搜索游戏根脚本目录，拒绝路径遍历
        candidate = scriptDir.resolve(filePath).normalize();
        if (!candidate.startsWith(scriptDir.normalize())) return null;
        if (Files.exists(candidate)) return candidate;
        if (!filePath.endsWith(".json")) {
            candidate = scriptDir.resolve(filePath + ".json").normalize();
            if (!candidate.startsWith(scriptDir.normalize())) return null;
            if (Files.exists(candidate)) return candidate;
        }

        return null;
    }
}
