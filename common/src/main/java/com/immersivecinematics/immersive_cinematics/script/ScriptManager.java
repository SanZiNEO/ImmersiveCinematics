package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistration;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.action.StartPlaybackAction;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScriptManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ScriptManager INSTANCE = new ScriptManager();

    /** 游戏根目录的全局脚本目录（服务端统一从根目录加载,不再同步进世界存档） */
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";

    /** 脚本递归加载的最大深度（子文件夹组织，防异常目录结构） */
    public static final int MAX_SCRIPT_DEPTH = 5;

    private final Map<String, CinematicScript> scripts = new LinkedHashMap<>();
    private boolean loaded = false;

    private ScriptManager() {}

    public void loadAll(MinecraftServer server) {
        scripts.clear();
        // 服务端统一从游戏根目录加载脚本（不依赖世界存档；播放时通过 S2C 包下发完整 JSON 给客户端）
        Path scriptDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        loadFromDir(scriptDir, true);
        loaded = true;
        LOGGER.info("Loaded {} scripts from {}", scripts.size(), scriptDir);
    }

    private void loadFromDir(Path dir, boolean overwrite) {
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad", "Failed to create scripts directory: " + dir, e);
            }
            return;
        }

        // 递归加载：支持子文件夹组织（深度 ≤ MAX_SCRIPT_DEPTH），只加载常规 .json 文件
        List<Path> jsonFiles;
        try (Stream<Path> stream = Files.walk(dir, MAX_SCRIPT_DEPTH)) {
            jsonFiles = stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                              .collect(Collectors.toList());
        } catch (IOException e) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad", "Failed to list scripts directory: " + dir, e);
            return;
        }

        for (Path file : jsonFiles) {
            try {
                String content = Files.readString(file);
                CinematicScript script = ScriptParser.parse(content);
                script.setRawJson(content);
                String id = script.getId();
                if (scripts.containsKey(id) && !overwrite) {
                    continue;
                }
                scripts.put(id, script);
                LOGGER.info("Loaded script: {} (id={}) from {}", script.getName(), id, toForwardRel(dir, file));
            } catch (Exception e) {
                // 脚本解析失败：写错误日志文件（作者排查），不影响其他脚本加载
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad",
                        "Failed to load script from " + toForwardRel(dir, file) + ": " + e.getMessage(), e);
            }
        }
    }

    public void registerAllTriggers() {
        TriggerEngine.INSTANCE.clear();
        List<TriggerRegistration> registrations = new ArrayList<>();
        for (CinematicScript script : scripts.values()) {
            ScriptMeta meta = script.getMeta();
            for (TriggerDefinition td : meta.getTriggers()) {
                TriggerType triggerType = TriggerRegistry.get(td.getType());
                if (triggerType == null) {
                    com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad",
                            "Unknown trigger type '" + td.getType() + "' in script '" + meta.getId() + "'");
                    continue;
                }
                JsonObject conditions = new JsonObject();
                for (Map.Entry<String, Object> entry : td.getConditions().entrySet()) {
                    convertToJson(conditions, entry.getKey(), entry.getValue());
                }
                if ("location".equals(td.getType()) && !hasValidLocationConditions(conditions)) {
                    com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad",
                            "脚本 '" + meta.getId() + "' 的 location 触发器缺少完整的 position/corner 坐标，已跳过该触发器");
                    continue;
                }
                int delayMs = (int)(td.getDelay() * 1000);
                JsonObject exitConditions = td.isOnEnter() && td.getExitBuffer() > 0f
                        ? Evaluators.expandConditions(conditions, td.getExitBuffer())
                        : null;
                // 前置依赖引用校验：指向不存在脚本 → 该触发器永不触发；自引用 → 永不解锁
                for (TriggerRequirement req : td.getRequires()) {
                    String reqId = scriptIdOfRequirement(req);
                    if (reqId == null) continue;
                    if (!scripts.containsKey(reqId)) {
                        com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad",
                                "脚本 '" + meta.getId() + "' 的触发器 '" + td.getType() + "' requires 指向不存在的脚本 '"
                                        + reqId + "'（该触发器将永不触发）");
                    } else if (reqId.equals(meta.getId())) {
                        com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("ScriptLoad",
                                "脚本 '" + meta.getId() + "' 的触发器 requires 自引用自身（可能永不解锁）");
                    }
                }
                registrations.add(new TriggerRegistration(
                        meta.getId(), td.getType() + "_" + meta.getId(),
                        triggerType, conditions,
                        List.of(new StartPlaybackAction(meta.getId())),
                        td.isRepeatable(),
                        delayMs,
                        td.isOnEnter(),
                        td.getExitBuffer(),
                        exitConditions,
                        td.getRequires()
                ));
            }
        }
        TriggerEngine.INSTANCE.registerAll(registrations);
        LOGGER.info("Registered {} trigger registrations", registrations.size());
    }

    public void reload(MinecraftServer server) {
        TriggerEngine.INSTANCE.clear();
        loadAll(server);
        // 重新注册全部触发器（registerAllTriggers 内部会重建索引）——
        // 之前只调 rebuildIndex 而 allRegistrations 已被 clear 清空，导致 reload 后触发器全部失效
        registerAllTriggers();
    }

    public CinematicScript getScript(String id) {
        return scripts.get(id);
    }

    public Collection<CinematicScript> getAllScripts() {
        return scripts.values();
    }

    public boolean isLoaded() { return loaded; }

    /** 从内置脚本类前置条件中取 script id；自定义类型返回 null（由注册者自行管理） */
    private static String scriptIdOfRequirement(TriggerRequirement req) {
        String type = req.getType();
        boolean builtinScriptType = "script_played".equals(type)
                || "script_started".equals(type)
                || "script_completed".equals(type);
        if (!builtinScriptType) return null;
        var data = req.getData();
        if (!data.has("script") || !data.get("script").isJsonPrimitive() || !data.get("script").getAsJsonPrimitive().isString()) {
            return null;
        }
        return data.get("script").getAsString();
    }

    /** 把 dir 下的文件转为向前斜杠的相对路径（目录结构组织的显示/日志用） */
    private static String toForwardRel(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }

    @SuppressWarnings("unchecked")
    private void convertToJson(com.google.gson.JsonObject target, String key, Object val) {
        if (val instanceof String s) target.addProperty(key, s);
        else if (val instanceof Number n) target.addProperty(key, n);
        else if (val instanceof Boolean b) target.addProperty(key, b);
        else if (val instanceof Map<?,?> m) {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            for (Map.Entry<?,?> e : ((Map<String, Object>) m).entrySet()) {
                convertToJson(obj, e.getKey().toString(), e.getValue());
            }
            target.add(key, obj);
        } else if (val instanceof List<?> l) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Object elem : l) {
                if (elem instanceof String s) arr.add(s);
                else if (elem instanceof Number n) arr.add(n);
                else if (elem instanceof Boolean b) arr.add(b);
                else if (elem instanceof Map<?,?> m) {
                    com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                    for (Map.Entry<?,?> e : ((Map<String, Object>) m).entrySet()) {
                        convertToJson(obj, e.getKey().toString(), e.getValue());
                    }
                    arr.add(obj);
                }
            }
            target.add(key, arr);
        }
    }

    /**
     * 校验 location 触发器条件是否完整：
     * 只有 dimension 也合法；有 position 则必须是含 x/y/z 的对象；
     * 有 corner1/corner2 则两个都必须是含 x/y/z 的对象。
     */
    private static boolean hasValidLocationConditions(JsonObject c) {
        boolean hasArea = c.has("position") || (c.has("corner1") && c.has("corner2"));
        if (!hasArea) return true;

        if (c.has("position")) {
            if (!c.get("position").isJsonObject()) return false;
            JsonObject p = c.getAsJsonObject("position");
            if (!p.has("x") || !p.has("y") || !p.has("z")) return false;
        }
        if (c.has("corner1") && c.has("corner2")) {
            if (!c.get("corner1").isJsonObject() || !c.get("corner2").isJsonObject()) return false;
            JsonObject c1 = c.getAsJsonObject("corner1");
            JsonObject c2 = c.getAsJsonObject("corner2");
            if (!c1.has("x") || !c1.has("y") || !c1.has("z")
                    || !c2.has("x") || !c2.has("y") || !c2.has("z")) return false;
        }
        return true;
    }
}
