package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistration;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.action.StartPlaybackAction;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
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

    /** 世界存档内的脚本目录 */
    private static final LevelResource WORLD_SCRIPT_PATH = new LevelResource("immersive_cinematics/scripts");

    /** 游戏根目录的全局脚本目录名 */
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";

    private final Map<String, CinematicScript> scripts = new LinkedHashMap<>();
    private boolean loaded = false;

    private ScriptManager() {}

    /** 首次启动时，将全局目录脚本复制到世界存档目录 */
    public void copyGlobalToWorld(MinecraftServer server) {
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        Path worldDir = server.getWorldPath(WORLD_SCRIPT_PATH);
        if (!Files.isDirectory(globalDir)) return;

        try {
            Files.createDirectories(worldDir);
            try (Stream<Path> files = Files.list(globalDir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(globalFile -> {
                    Path target = worldDir.resolve(globalFile.getFileName());
                    if (!Files.exists(target)) {
                        try {
                            Files.copy(globalFile, target);
                            LOGGER.info("Copied script {} to world", globalFile.getFileName());
                        } catch (IOException e) {
                            LOGGER.error("Failed to copy {} to world: {}", globalFile.getFileName(), e.getMessage());
                        }
                    }
                });
            }
        } catch (IOException e) {
            LOGGER.error("Failed to copy global scripts to world: {}", e.getMessage());
        }
    }

    public void loadAll(MinecraftServer server) {
        scripts.clear();
        Path worldScriptDir = server.getWorldPath(WORLD_SCRIPT_PATH);
        loadFromDir(worldScriptDir, true);
        loaded = true;
        LOGGER.info("Loaded {} scripts from world directory", scripts.size());
    }

    private void loadFromDir(Path dir, boolean overwrite) {
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.error("Failed to create scripts directory: {}", dir, e);
            }
            return;
        }

        List<Path> jsonFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            jsonFiles = stream.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to list scripts directory: {}", dir, e);
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
                LOGGER.info("Loaded script: {} (id={}) from {}", script.getName(), id, dir);
            } catch (Exception e) {
                LOGGER.error("Failed to load script from {}", file.getFileName(), e);
            }
        }
    }

    public void registerAllTriggers() {
        List<TriggerRegistration> registrations = new ArrayList<>();
        for (CinematicScript script : scripts.values()) {
            ScriptMeta meta = script.getMeta();
            for (TriggerDefinition td : meta.getTriggers()) {
                TriggerType triggerType = TriggerRegistry.get(td.getType());
                if (triggerType == null) {
                    LOGGER.warn("Unknown trigger type '{}' in script '{}'", td.getType(), meta.getId());
                    continue;
                }
                JsonObject conditions = new JsonObject();
                for (Map.Entry<String, Object> entry : td.getConditions().entrySet()) {
                    convertToJson(conditions, entry.getKey(), entry.getValue());
                }
                int delayMs = (int)(td.getDelay() * 1000);
                registrations.add(new TriggerRegistration(
                        meta.getId(), td.getType() + "_" + meta.getId(),
                        triggerType, conditions,
                        List.of(new StartPlaybackAction(meta.getId())),
                        td.isRepeatable(),
                        delayMs
                ));
            }
        }
        TriggerEngine.INSTANCE.registerAll(registrations);
        LOGGER.info("Registered {} trigger registrations", registrations.size());
    }

    public void reload(MinecraftServer server) {
        TriggerEngine.INSTANCE.clear();
        loadAll(server);
        TriggerEngine.INSTANCE.rebuildIndex();
    }

    public CinematicScript getScript(String id) {
        return scripts.get(id);
    }

    public Collection<CinematicScript> getAllScripts() {
        return scripts.values();
    }

    public boolean isLoaded() { return loaded; }

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
}
