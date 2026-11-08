package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.stream.Stream;

/**
 * {@code /icinematics} 命令树：播放 / 停止 / 重载 / 校验。
 *
 * 脚本一律从服务端游戏根目录的 {@code immersive_cinematics/scripts/} 读取。命令路径即该目录下的
 * 相对路径（{@code /} 分隔、不带 {@code .json}）；文件 → 命令路径见 {@link #toCommandPath}，
 * 命令输入 → 候选路径见 {@link #scriptPathCandidates}。
 */
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
                        .map(p -> toCommandPath(globalDir, p))
                        .sorted()
                        .forEach(builder::suggest);
            } catch (IOException e) {
                // Tab 补全是 best-effort：目录不可读时无建议即可，但这是真实问题，报 WARN 可见
                LOGGER.warn("Tab 补全扫描脚本目录失败: {} ({})", e.getMessage(), globalDir);
            }
        }
        return SharedSuggestionProvider.suggest(new String[0], builder);
    };

    /**
     * stop 的 script 参数补全：已加载脚本 id（{@code meta.id} = 网络层脚本 id 口径）与脚本目录下
     * 相对路径（命令路径）的并集 —— 两种写法都能被 stop 接受。
     */
    private static final SuggestionProvider<CommandSourceStack> LOADED_SCRIPT_SUGGESTIONS = (ctx, builder) -> {
        java.util.Set<String> suggestions = new java.util.LinkedHashSet<>();
        ScriptManager.INSTANCE.getAllScripts().stream().map(CinematicScript::getId).forEach(suggestions::add);
        MinecraftServer server = ctx.getSource().getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        if (Files.isDirectory(globalDir)) {
            try (Stream<Path> files = Files.walk(globalDir, MAX_SCRIPT_DEPTH)) {
                files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                        .map(p -> toCommandPath(globalDir, p))
                        .sorted()
                        .forEach(suggestions::add);
            } catch (IOException e) {
                LOGGER.warn("Tab 补全扫描脚本目录失败: {} ({})", e.getMessage(), globalDir);
            }
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    };

    /** 把 globalDir 下的文件转为命令路径：scripts/ 下的相对路径，{@code /} 分隔，去掉末尾 .json。 */
    private static String toCommandPath(Path globalDir, Path p) {
        String rel = toForwardRel(globalDir, p);
        return rel.endsWith(".json") ? rel.substring(0, rel.length() - ".json".length()) : rel;
    }

    /** 把 globalDir 下的文件转为向前斜杠的相对路径 */
    private static String toForwardRel(Path globalDir, Path p) {
        return globalDir.relativize(p).toString().replace('\\', '/');
    }

    /** 把 {@code /icinematics} 及其子命令注册到给定 dispatcher（服务端启动时调用一次）。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("icinematics")
                .then(Commands.literal("play")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("file", ResourceLocationArgument.id())
                                .suggests(SCRIPT_SUGGESTIONS)
                                .executes(guarded("play", CinematicCommand::playScript))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(guarded("play", CinematicCommand::playScript)))))
                .then(Commands.literal("stop")
                        .executes(guarded("stop", CinematicCommand::stopScript))
                        .then(Commands.argument("script", ResourceLocationArgument.id())
                                .suggests(LOADED_SCRIPT_SUGGESTIONS)
                                .executes(guarded("stop", CinematicCommand::stopScript))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(guarded("stop", CinematicCommand::stopScript))))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(guarded("stop", CinematicCommand::stopScript))))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(guarded("reload", CinematicCommand::reloadScripts)))
                .then(Commands.literal("validate")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("file", ResourceLocationArgument.id())
                                .suggests(SCRIPT_SUGGESTIONS)
                                .executes(guarded("validate", CinematicCommand::validateScriptFile))))
        );
    }

    /**
     * 命令执行边界：executor 内任何未预期异常都不允许冒泡回 Brigadier（命令源只会看到通用报错，
     * 且命令常由 EVENT 轨 / 命令方块触发，异常反馈容易被吞掉）。
     *
     * <p>{@link CommandSyntaxException} 原样上抛（Brigadier 用它渲染语法/参数提示）；其余 Exception
     * 先过致命错误判定，再记录完整栈并向命令源回显内部错误，返回 0。
     */
    private static Command<CommandSourceStack> guarded(String name, Command<CommandSourceStack> command) {
        return ctx -> {
            try {
                return command.run(ctx);
            } catch (CommandSyntaxException e) {
                throw e;
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                LOGGER.error("命令执行失败: {}", name, e);
                ctx.getSource().sendFailure(Component.literal("§c内部错误: " + e.getMessage()));
                return 0;
            }
        };
    }

    private static int playScript(CommandContext<CommandSourceStack> context) {
        ResourceLocation location = ResourceLocationArgument.getId(context, "file");
        List<String> candidates = scriptPathCandidates(location);
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        Path scriptPath = findScriptFile(candidates, globalDir);
        if (scriptPath == null) {
            source.sendFailure(scriptNotFound(candidates, globalDir));
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

        // 握手：登记 ACK，超时重发（幂等：playCinematic 有打断/排队逻辑）
        final Collection<ServerPlayer> ackTargets = targets;
        String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
        // 传输上限预检：超限脚本给出明确提示，不再落到通用“意外错误”，也不登记 ACK 触发重发
        String sizeError = S2CPlayScriptPacket.validate(json);
        if (sizeError != null) {
            // 命令入口是边界：拒绝播放必须留痕（脚本命令常由 EVENT 轨触发，失败反馈可能被命令源吞掉）
            LOGGER.warn("拒绝播放脚本 {}：{}", scriptPath, sizeError);
            source.sendFailure(Component.literal("§c" + sizeError));
            return 0;
        }
        // 规则命令信任边界：动作表按即将下发的脚本版本编译；观看者授权 = 本次目标玩家。
        // 客户端播放回执只能消费这份授权，不能自证观看者或新建实例。
        com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager.INSTANCE
                .primeCommandTable(script);
        java.util.List<java.util.UUID> grantTargets = new java.util.ArrayList<>(targets.size());
        for (ServerPlayer p : targets) {
            grantTargets.add(p.getUUID());
        }
        com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager.INSTANCE
                .createGrant(script.getId(), refId, grantTargets);
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.expect(refId, () -> {
            for (ServerPlayer p : ackTargets) {
                S2CPlayScriptPacket.send(p, json, refId);
            }
        });
        for (ServerPlayer player : targets) {
            S2CPlayScriptPacket.send(player, json, refId);
        }

        final int count = targets.size();
        LOGGER.info("已向 {} 名玩家推送脚本: {} (内容末端: {})",
                count, script.getMeta().getName(),
                script.hasFiniteContentEnd()
                        ? String.format("%.1fs", script.contentEnd())
                        : "无限/无有限末端");
        return 1;
    }

    private static int stopScript(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        // 可选 script 参数：不写 = 停全部；写了 = 只停该脚本（在播实例优先，否则取消排队项）
        String scriptId;
        try {
            ResourceLocation location = ResourceLocationArgument.getId(context, "script");
            scriptId = resolveStopScriptId(server, location);
        } catch (IllegalArgumentException e) {
            scriptId = "";
        }

        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "players");
        } catch (IllegalArgumentException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // 未指定 players 或选择器解析失败 → 回退全体在线玩家（stop 的默认目标语义）
            targets = server.getPlayerList().getPlayers();
        }

        // 握手：登记 ACK，超时重发（幂等：重复 stop 安全）
        final Collection<ServerPlayer> ackTargets = targets;
        final String stopScriptId = scriptId;
        String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.expect(refId, () -> {
            for (ServerPlayer p : ackTargets) {
                S2CStopScriptPacket.send(p, stopScriptId, "", refId);
            }
        });
        for (ServerPlayer player : targets) {
            S2CStopScriptPacket.send(player, stopScriptId, "", refId);
        }

        final int count = targets.size();
        if (stopScriptId.isEmpty()) {
            LOGGER.info("已向 {} 名玩家发送停止指令", count);
        } else {
            LOGGER.info("已向 {} 名玩家发送停止脚本指令: {}", count, stopScriptId);
        }
        return 1;
    }

    /**
     * stop 的 script 参数 → 网络层脚本 id（{@code meta.id}）：先按命令路径（与 play / validate 同一候选口径）
     * 定位脚本文件并解析取 meta.id；文件不存在 / 解析失败 → 原样返回参数（允许直接写已加载脚本 id）。
     */
    private static String resolveStopScriptId(MinecraftServer server, ResourceLocation location) {
        String literal = location.toString();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        Path file = findScriptFile(scriptPathCandidates(location), globalDir);
        if (file != null) {
            try {
                return ScriptParser.parse(Files.readString(file)).getId();
            } catch (Exception e) {
                LOGGER.warn("stop: 脚本文件解析失败，按字面量下发脚本 id: {} ({})", file, e.getMessage());
            }
        } else if (ScriptManager.INSTANCE.getScript(literal) == null) {
            LOGGER.warn("stop: 既不是已加载脚本 id 也没有同名脚本文件，按字面量下发: {}", literal);
        }
        return literal;
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
        List<String> candidates = scriptPathCandidates(location);
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        Path scriptPath = findScriptFile(candidates, globalDir);
        if (scriptPath == null) {
            source.sendFailure(scriptNotFound(candidates, globalDir));
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
        List<String> errors = com.immersivecinematics.immersive_cinematics.script.ScriptValidator.errorsOf(issues);
        if (errors.isEmpty()) {
            String suffix = issues.isEmpty() ? "" : "（" + issues.size() + " 条警告）";
            source.sendSuccess(() -> Component.literal("§a校验通过: " + scriptPath.getFileName() + suffix), false);
            if (!issues.isEmpty()) {
                StringBuilder warn = new StringBuilder("§e警告:\n");
                for (String issue : issues) {
                    warn.append("§7  - ").append(issue).append("\n");
                }
                source.sendSuccess(() -> Component.literal(warn.toString()), false);
            }
            return 1;
        }

        StringBuilder msg = new StringBuilder("§c发现 " + errors.size() + " 个问题:\n");
        for (String issue : issues) {
            msg.append("§7  - ").append(issue).append("\n");
        }
        source.sendSuccess(() -> Component.literal(msg.toString()), false);
        return 1;
    }

    /**
     * 命令输入 → 候选相对路径（{@code scripts/} 下，{@code /} 分隔、不带 .json）：
     * {@code minecraft} namespace 用 path 本身，其余用字面 {@code namespace/path}。
     */
    private static List<String> scriptPathCandidates(ResourceLocation location) {
        String namespace = location.getNamespace();
        String path = location.getPath();
        if ("minecraft".equals(namespace)) return List.of(path);
        return List.of(namespace + "/" + path);
    }

    /**
     * 定位脚本文件：候选先精确路径、再补 {@code .json}，存在的胜出。
     * 越界候选（绝对路径、{@code ../} 逃逸）被拒绝，不会读到 {@code scriptDir} 之外。
     */
    private static Path findScriptFile(List<String> candidates, Path scriptDir) {
        Path base = scriptDir.normalize();
        for (String candidate : candidates) {
            Path found = resolveScriptCandidate(candidate, base);
            if (found != null) return found;
        }
        return null;
    }

    /** 单个候选 → 存在的文件；越界返回 null。先精确、再补 .json（候选本身已带 .json 时不重复补）。 */
    private static Path resolveScriptCandidate(String relPath, Path base) {
        Path candidate = base.resolve(relPath).normalize();
        if (!candidate.startsWith(base)) return null;
        if (Files.isRegularFile(candidate)) return candidate;
        if (!relPath.endsWith(".json")) {
            Path withJson = base.resolve(relPath + ".json").normalize();
            if (!withJson.startsWith(base)) return null;
            if (Files.isRegularFile(withJson)) return withJson;
        }
        return null;
    }

    /** 文件不存在时的统一提示：列出实际尝试过的候选路径（含 .json 变体），并提示用 {@code /} 写明确路径。 */
    private static Component scriptNotFound(List<String> candidates, Path scriptDir) {
        Path base = scriptDir.normalize();
        StringBuilder attempts = new StringBuilder();
        int n = 0;
        for (String candidate : candidates) {
            attempts.append("\n§7  ").append(++n).append(". ").append(base.resolve(candidate));
            if (!candidate.endsWith(".json")) {
                attempts.append("\n§7  ").append(++n).append(". ").append(base.resolve(candidate + ".json"));
            }
        }
        return Component.literal("§c脚本文件不存在: " + String.join(" 或 ", candidates)
                + "\n§7已尝试的路径:" + attempts
                + "\n§7提示: 用 / 写明确路径（如 camera_center/test_x），.json 可省略"
                + "\n§7请将 .json 脚本文件放入: " + base);
    }
}
