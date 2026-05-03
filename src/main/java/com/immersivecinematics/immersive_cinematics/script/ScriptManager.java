package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistration;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.action.*;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScriptManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ScriptManager INSTANCE = new ScriptManager();

    private static final LevelResource SCRIPT_PATH = new LevelResource("immersive_cinematics/scripts");

    private final Map<String, CinematicScript> scripts = new LinkedHashMap<>();
    private boolean loaded = false;

    private ScriptManager() {}

    public void loadAll(MinecraftServer server) {
        scripts.clear();
        Path scriptDir = server.getWorldPath(SCRIPT_PATH);
        if (!Files.isDirectory(scriptDir)) {
            try {
                Files.createDirectories(scriptDir);
            } catch (IOException e) {
                LOGGER.error("Failed to create scripts directory", e);
            }
            loaded = true;
            return;
        }

        List<Path> jsonFiles;
        try (Stream<Path> stream = Files.list(scriptDir)) {
            jsonFiles = stream.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to list scripts directory", e);
            loaded = true;
            return;
        }

        for (Path file : jsonFiles) {
            try {
                String content = Files.readString(file);
                CinematicScript script = ScriptParser.parse(content);
                script.setRawJson(content);
                String id = script.getId();
                if (scripts.containsKey(id)) {
                    LOGGER.warn("Duplicate script id '{}' in file {}, overwriting", id, file.getFileName());
                }
                scripts.put(id, script);
                LOGGER.info("Loaded script: {} (id={})", script.getName(), id);
            } catch (Exception e) {
                LOGGER.error("Failed to load script from {}", file.getFileName(), e);
            }
        }

        loaded = true;
        LOGGER.info("Loaded {} scripts from {}", scripts.size(), scriptDir);
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
                registrations.add(new TriggerRegistration(
                        meta.getId(), td.getType() + "_" + meta.getId(),
                        triggerType, conditions,
                        List.of(new StartPlaybackAction(meta.getId())),
                        td.isRepeatable()
                ));
            }
        }
        TriggerEngine.INSTANCE.registerAll(registrations);
        LOGGER.info("Registered {} trigger registrations", registrations.size());
    }

    public void reload(MinecraftServer server) {
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
}
