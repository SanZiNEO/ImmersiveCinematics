package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.immersivecinematics.immersive_cinematics.script.ScriptValidator;
import com.immersivecinematics.immersive_cinematics.script.template.ClipTemplate;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateArgs;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateParam;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateRegistry;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateScriptAssembler;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code /icinematics} 命令树：播放 / 停止 / 重载 / 校验 / 片段模板生成。
 *
 * 脚本一律从服务端游戏根目录的 {@code immersive_cinematics/scripts/} 读取。命令路径即该目录下的
 * 相对路径（{@code /} 分隔、不带 {@code .json}）；文件 → 命令路径见 {@link #toCommandPath}，
 * 命令输入 → 候选路径见 {@link #scriptPathCandidates}（colon 形式仅作兼容别名）。
 */
public class CinematicCommand {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/Command");
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";

    /** 模板生成脚本的落地子目录（与手写脚本分开，便于整体清理/重生成）。 */
    private static final String GENERATED_SUBDIR = "generated";

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

    /** 模板 id 补全（片段级模板注册表的内置库）。 */
    private static final SuggestionProvider<CommandSourceStack> TEMPLATE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(TemplateRegistry.ids(), builder);

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
                .then(Commands.literal("template")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("list")
                                .executes(guarded("template list", CinematicCommand::listTemplates)))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(TEMPLATE_SUGGESTIONS)
                                .executes(guarded("template generate", CinematicCommand::generateFromTemplate))
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(guarded("template generate", CinematicCommand::generateFromTemplate)))))
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
        // 结构/方块来源解析：脚本关键帧中的 look_at_target_structure、position.relative_origin 等
        // → 服务端定位最近结构/方块 → 替换为坐标（坐标按执行者所在维度/位置定位；脚本文件本身不被修改）
        final String resolvedJson = com.immersivecinematics.immersive_cinematics.util.ScriptStructureResolver
                .resolveTargets(json, source.getLevel(), source.getPosition());
        // 传输上限预检：超限脚本给出明确提示，不再落到通用“意外错误”，也不登记 ACK 触发重发
        String sizeError = S2CPlayScriptPacket.validate(resolvedJson);
        if (sizeError != null) {
            // 命令入口是边界：拒绝播放必须留痕（脚本命令常由 EVENT 轨触发，失败反馈可能被命令源吞掉）
            LOGGER.warn("拒绝播放脚本 {}：{}", scriptPath, sizeError);
            source.sendFailure(Component.literal("§c" + sizeError));
            return 0;
        }
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
     * /icinematics template list — 列出内置片段模板与其参数（填参说明）。
     */
    private static int listTemplates(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<ClipTemplate> templates = TemplateRegistry.all();
        if (templates.isEmpty()) {
            source.sendFailure(Component.literal("§c模板库为空"));
            return 0;
        }
        StringBuilder msg = new StringBuilder("§6内置片段模板 " + templates.size() + " 个：\n");
        for (ClipTemplate t : templates) {
            msg.append("§e/icinematics template ").append(t.id())
                    .append(" §8[").append(t.trackType().name()).append("] §f").append(t.name()).append("\n");
            msg.append("§7    ").append(t.description()).append("\n");
            for (TemplateParam p : t.params()) {
                msg.append("§8      ").append(p.key()).append("=§f").append(p.defaultValue())
                        .append(" §7").append(p.label());
                if (!p.enumValues().isEmpty()) {
                    msg.append(" §8[").append(String.join("|", p.enumValues())).append("]");
                }
                msg.append("\n");
            }
        }
        msg.append("§7保留 key：§fname§7=<文件名，默认模板 id>、§fstart§7=<起始秒，默认 0>\n");
        msg.append("§7生成到 scripts/generated/ 并当场校验；播放：§f/icinematics play generated/<name>");
        source.sendSuccess(() -> Component.literal(msg.toString()), false);
        return 1;
    }

    /**
     * /icinematics template &lt;id&gt; [key=value ...] — 展开片段模板为标准脚本 JSON：
     * 写入 {@code scripts/generated/<name>.json}，并当场走 {@link ScriptValidator}
     * （与 {@code /icinematics validate} 同源）。产物与手写脚本同构，可继续编辑 / 播放。
     */
    private static int generateFromTemplate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        ClipTemplate template = TemplateRegistry.get(id);
        if (template == null) {
            source.sendFailure(Component.literal("§c未知模板: " + id
                    + "\n§7可用: " + String.join(", ", TemplateRegistry.ids())
                    + "\n§7查看参数: /icinematics template list"));
            return 0;
        }

        Map<String, String> raw = new LinkedHashMap<>();
        String scriptId = id;
        float startTime = 0f;
        String paramsText;
        try {
            paramsText = StringArgumentType.getString(context, "params");
        } catch (IllegalArgumentException absent) {
            paramsText = "";   // 未给参数 → 全部走默认值
        }
        for (String token : paramsText.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            if (eq <= 0) {
                source.sendFailure(Component.literal("§c参数格式应为 key=value，收到: " + token));
                return 0;
            }
            String key = token.substring(0, eq);
            String value = token.substring(eq + 1);
            if ("name".equals(key)) {
                scriptId = value;
            } else if ("start".equals(key)) {
                try {
                    startTime = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    source.sendFailure(Component.literal("§cstart 不是数字: " + value));
                    return 0;
                }
            } else if (TemplateRegistry.isDeclaredParam(template, key)) {
                raw.put(key, value);
            } else {
                source.sendFailure(Component.literal("§c模板 " + id + " 没有参数: " + key
                        + "（保留 key: name / start；参数表见 /icinematics template list）"));
                return 0;
            }
        }
        if (!scriptId.matches("[a-z0-9_]+")) {
            source.sendFailure(Component.literal("§c文件名非法: " + scriptId
                    + "（只允许小写字母 / 数字 / 下划线——生成物要能直接用作脚本 id）"));
            return 0;
        }

        String json;
        try {
            TemplateArgs args = TemplateArgs.parse(template.params(), raw, startTime);
            json = TemplateScriptAssembler.assembleJson(template, args, scriptId);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c模板展开失败: " + e.getMessage()));
            return 0;
        }

        // 生成即校验：产物必须与手写脚本一样过 validator，不过就不落地
        java.util.Set<String> knownIds = ScriptManager.INSTANCE.getAllScripts().stream()
                .map(CinematicScript::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> issues = ScriptValidator.validate(json, knownIds);
        if (!issues.isEmpty()) {
            StringBuilder msg = new StringBuilder("§c模板 " + id + " 的产物未通过校验（未写入文件）：\n");
            for (String issue : issues) {
                msg.append("§7  - ").append(issue).append("\n");
            }
            source.sendSuccess(() -> Component.literal(msg.toString()), false);
            return 0;
        }

        MinecraftServer server = source.getServer();
        Path outDir = server.getServerDirectory().toPath().toAbsolutePath()
                .resolve(GLOBAL_SCRIPT_DIR).resolve(GENERATED_SUBDIR);
        Path outFile = outDir.resolve(scriptId + ".json");
        boolean existed = Files.exists(outFile);
        try {
            Files.createDirectories(outDir);
            Files.writeString(outFile, json);
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c写入脚本失败: " + e.getMessage()));
            return 0;
        }

        final String cmdPath = GENERATED_SUBDIR + "/" + scriptId;
        final String out = outFile.toString();
        final String overwritten = existed ? "§7(覆盖已存在文件) " : "";
        source.sendSuccess(() -> Component.literal("§a已生成脚本 " + overwritten + out
                + "\n§7模板: " + id + " §7| 轨道: " + template.trackType().name() + " §7| 校验通过"
                + "\n§7播放: §f/icinematics play " + cmdPath
                + "§7 | 再校验: §f/icinematics validate " + cmdPath), false);
        LOGGER.info("模板 {} 生成脚本 {}（参数 {}）", id, out, raw);
        return 1;
    }

    /**
     * 命令输入 → 候选相对路径列表（{@code scripts/} 下，{@code /} 分隔、不带 .json），按优先级排列：
     * <ol>
     *   <li>{@code minecraft} namespace：只有 path 本身（根目录或 path 内含层级）；</li>
     *   <li>其他 namespace：先字面 {@code namespace/path}，再旧解码 {@code namespace.replace('_', '/')/path}
     *       （与字面相同则去重）。</li>
     * </ol>
     * 字面候选存在时绝不回退旧解码，见 {@link #findScriptFile}。
     */
    private static List<String> scriptPathCandidates(ResourceLocation location) {
        String namespace = location.getNamespace();
        String path = location.getPath();
        if ("minecraft".equals(namespace)) return List.of(path);
        String literal = namespace + "/" + path;
        String legacy = namespace.replace('_', '/') + "/" + path;
        return legacy.equals(literal) ? List.of(literal) : List.of(literal, legacy);
    }

    /**
     * 按候选顺序定位脚本文件：每个候选先精确路径、再补 {@code .json}，第一个存在的胜出。
     * 越界候选（绝对路径、{@code ../} 逃逸）被拒绝，不会读到 {@code scriptDir} 之外。
     * 若后面的候选也能命中，说明输入有歧义（字面 / 旧解码同时存在），字面胜出并记 WARN。
     */
    private static Path findScriptFile(List<String> candidates, Path scriptDir) {
        Path base = scriptDir.normalize();
        for (int i = 0; i < candidates.size(); i++) {
            Path found = resolveScriptCandidate(candidates.get(i), base);
            if (found == null) continue;
            for (int j = i + 1; j < candidates.size(); j++) {
                Path shadowed = resolveScriptCandidate(candidates.get(j), base);
                if (shadowed != null) {
                    LOGGER.warn("脚本路径存在歧义: {} 与 {} 都能命中，已按字面路径选择 {}；请用 / 写明确路径",
                            candidates.get(i), candidates.get(j), found);
                    break;
                }
            }
            return found;
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
