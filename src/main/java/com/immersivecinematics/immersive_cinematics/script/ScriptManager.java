package com.immersivecinematics.immersive_cinematics.script;

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
    private Path globalScriptDir;

    private ScriptManager() {}

    public void loadAll(MinecraftServer server) {
        scripts.clear();

        // 1. 游戏根目录的全局脚本
        globalScriptDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        loadFromDir(globalScriptDir, false);

        // 2. 世界存档内的脚本（覆盖同名 ID）
        Path worldScriptDir = server.getWorldPath(WORLD_SCRIPT_PATH);
        loadFromDir(worldScriptDir, true);

        loaded = true;
        LOGGER.info("Loaded {} scripts (global={})", scripts.size(), globalScriptDir);
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
                    Object val = entry.getValue();
                    if (val instanceof String s) conditions.addProperty(entry.getKey(), s);
                    else if (val instanceof Number n) conditions.addProperty(entry.getKey(), n);
                    else if (val instanceof Boolean b) conditions.addProperty(entry.getKey(), b);
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

    public Path getGlobalScriptDir() {
        return globalScriptDir;
    }

    public boolean isLoaded() { return loaded; }
}
