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
import net.minecraft.world.phys.Vec3;

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

    private static final SuggestionProvider<CommandSourceStack> SCRIPT_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        if (Files.isDirectory(globalDir)) {
            try (Stream<Path> files = Files.list(globalDir)) {
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
                        .requires(s -> s.hasPermission(2))
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
                .then(Commands.literal("validate")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests(SCRIPT_SUGGESTIONS)
                                .executes(CinematicCommand::validateScriptFile)))
        );
    }

    private static int playScript(CommandContext<CommandSourceStack> context) {
        String filePath = StringArgumentType.getString(context, "file");
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
            targets = server.getPlayerList().getPlayers();
        }

        // N1：握手 — 登记 ACK，超时重发（幂等：playCinematic 有打断/排队逻辑）
        final Collection<ServerPlayer> ackTargets = targets;
        String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
        // 结构坐标解析：脚本关键帧中的 look_at_target_structure 字段 → 服务端定位结构中心 → 替换为 look_at_target_x/y/z
        // （坐标按执行者所在维度/位置定位最近结构，随脚本 JSON 推送；脚本文件本身不被修改）
        final String resolvedJson = resolveStructureTargets(json, source);
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

    /**
     * 服务端结构坐标解析：遍历脚本关键帧，把结构目标替换为结构中心坐标——
     * look_at_target_structure → look_at_target_x/y/z；position.relative_origin（结构 id）→
     * "coordinate" + relative_origin_x/y/z。脚本文件本身不被修改，只替换推送内容。
     * 定位失败保留原字段（客户端该端无目标，片段按空处理）。
     */
    private static String resolveStructureTargets(String json, CommandSourceStack source) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray tracks = root.getAsJsonObject("timeline").getAsJsonArray("tracks");
            if (tracks == null) return json;
            java.util.Map<String, Vec3> posCache = new java.util.HashMap<>();
            boolean changed = false;
            for (com.google.gson.JsonElement te : tracks) {
                if (!te.isJsonObject()) continue;
                com.google.gson.JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
                if (clips == null) continue;
                for (com.google.gson.JsonElement ce : clips) {
                    if (!ce.isJsonObject()) continue;
                    com.google.gson.JsonArray kfs = ce.getAsJsonObject().getAsJsonArray("keyframes");
                    if (kfs == null) continue;
                    for (com.google.gson.JsonElement ke : kfs) {
                        if (!ke.isJsonObject()) continue;
                        com.google.gson.JsonObject kf = ke.getAsJsonObject();
                        if (kf.has("look_at_target_structure")) {
                            changed |= replaceStructureTarget(kf, "look_at_target_structure",
                                    "look_at_target_x", "look_at_target_y", "look_at_target_z", posCache, source);
                        }
                        if (kf.has("position") && kf.get("position").isJsonObject()) {
                            com.google.gson.JsonObject pos = kf.getAsJsonObject("position");
                            if (pos.has("relative_origin")) {
                                changed |= replaceStructureTarget(pos, "relative_origin",
                                        "relative_origin_x", "relative_origin_y", "relative_origin_z", posCache, source);
                            }
                        }
                    }
                }
            }
            return changed ? new com.google.gson.Gson().toJson(root) : json;
        } catch (Exception e) {
            LOGGER.warn("结构坐标替换失败（原脚本照常推送）: {}", e.getMessage());
            return json;
        }
    }

    /**
     * 把对象内的结构字段替换为结构中心坐标：
     * sourceField（结构 id）→ 解析成功：写入 targetX/Y/Z 并移除 sourceField；
     * 解析失败（或 sourceField 非结构 id，如 "coordinate"）：保留原字段。
     *
     * @return 是否发生了替换
     */
    private static boolean replaceStructureTarget(com.google.gson.JsonObject obj, String sourceField,
                                                  String targetX, String targetY, String targetZ,
                                                  java.util.Map<String, Vec3> posCache, CommandSourceStack source) {
        String structureId = obj.get(sourceField).getAsString();
        if ("coordinate".equals(structureId) || structureId.isEmpty()) return false;
        Vec3 pos = posCache.containsKey(structureId) ? posCache.get(structureId) : locateStructure(source, structureId);
        posCache.put(structureId, pos);
        if (pos != null) {
            obj.addProperty(targetX, (float) pos.x);
            obj.addProperty(targetY, (float) pos.y);
            obj.addProperty(targetZ, (float) pos.z);
            obj.remove(sourceField);
            return true;
        }
        LOGGER.warn("结构 '{}' 定位失败，脚本保留 structure 字段（客户端该端无目标，片段按空处理）", structureId);
        return false;
    }

    /** 服务端结构定位：原版 /locate 同源（执行者所在维度，以执行者位置为中心搜 100 区块），返回结构 bounding box 中心 */
    private static Vec3 locateStructure(CommandSourceStack source, String structureId) {
        if (source.getLevel() instanceof net.minecraft.server.level.ServerLevel) {
            net.minecraft.server.level.ServerLevel serverLevel =
                    (net.minecraft.server.level.ServerLevel) source.getLevel();
            return com.immersivecinematics.immersive_cinematics.util.StructureLocator.locateCenter(
                    serverLevel, structureId, net.minecraft.core.BlockPos.containing(source.getPosition()), 100);
        }
        return null;
    }

    private static int stopScript(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "players");
        } catch (IllegalArgumentException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
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

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);

        CameraManager mgr = CameraManager.INSTANCE;
        if (!mgr.isActive()) {
            LOGGER.info("相机未激活 | 全局脚本目录: {}", globalDir);
            return 0;
        }

        if (mgr.isScriptMode()) {
            CinematicScript script = mgr.getScriptPlayer().getScript();
            String name = script != null ? script.getName() : "未知";
            float remaining = mgr.getScriptPlayer().getRemainingTime();
            LOGGER.info("脚本模式: {} (剩余: {}s)", name, String.format("%.1f", remaining));
        } else {
            LOGGER.info("测试模式 (P键激活)");
        }

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
        String filePath = StringArgumentType.getString(context, "file");
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

        List<String> issues = com.immersivecinematics.immersive_cinematics.script.ScriptValidator.validate(json);
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
