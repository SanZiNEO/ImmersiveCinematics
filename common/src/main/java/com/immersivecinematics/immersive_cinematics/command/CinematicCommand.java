package com.immersivecinematics.immersive_cinematics.command;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.immersivecinematics.immersive_cinematics.script.ScriptValidator;
import com.immersivecinematics.immersive_cinematics.script.template.ClipTemplate;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateArgs;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateParam;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateRegistry;
import com.immersivecinematics.immersive_cinematics.script.template.TemplateScriptAssembler;
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

    /** 模板 id 补全（片段级模板注册表的内置库）。 */
    private static final SuggestionProvider<CommandSourceStack> TEMPLATE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(TemplateRegistry.ids(), builder);

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
                .then(Commands.literal("template")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("list")
                                .executes(CinematicCommand::listTemplates))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(TEMPLATE_SUGGESTIONS)
                                .executes(CinematicCommand::generateFromTemplate)
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(CinematicCommand::generateFromTemplate))))
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
                S2CStopScriptPacket.send(p, "", "", refId);
            }
        });
        for (ServerPlayer player : targets) {
            S2CStopScriptPacket.send(player, "", "", refId);
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
        msg.append("§7生成到 scripts/generated/ 并当场校验；播放：§f/icinematics play generated:<name>");
        source.sendSuccess(() -> Component.literal(msg.toString()), false);
        return 1;
    }

    /**
     * /icinematics template &lt;id&gt; [key=value ...] — 展开片段模板为**标准脚本 JSON**：
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

        final String cmdId = GENERATED_SUBDIR + ":" + scriptId;
        final String out = outFile.toString();
        final String overwritten = existed ? "§7(覆盖已存在文件) " : "";
        source.sendSuccess(() -> Component.literal("§a已生成脚本 " + overwritten + out
                + "\n§7模板: " + id + " §7| 轨道: " + template.trackType().name() + " §7| 校验通过"
                + "\n§7播放: §f/icinematics play " + cmdId
                + "§7 | 再校验: §f/icinematics validate " + cmdId), false);
        LOGGER.info("模板 {} 生成脚本 {}（参数 {}）", id, out, raw);
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
