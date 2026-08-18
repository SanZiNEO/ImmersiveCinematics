package com.immersivecinematics.immersive_cinematics.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.Config;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric 平台配置实现。
 * <p>
 * 使用 JSON 文件存储配置，路径为 {@code config/immersive_cinematics.json}。
 * 文件不存在时使用默认值自动创建。
 */
public class FabricConfig implements Config.ConfigProvider {

    public static final FabricConfig INSTANCE = new FabricConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("immersive_cinematics.json");

    private JsonObject root;

    private FabricConfig() {
        // 由 INSTANCE 静态初始化
    }

    @Override
    public Config.ConfigValues load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                root = GSON.fromJson(reader, JsonObject.class);
            } catch (IOException e) {
                // 配置读取失败 → 回退默认配置；作者应看到（WARN）
                org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/Config")
                        .warn("配置读取失败，将使用默认配置: {}", e.getMessage());
                root = null;
            }
        }

        if (root == null) {
            root = new JsonObject();
            saveDefaults();
        }

        return new Config.ConfigValues(
                getInt("skipHoldThresholdMs", 3000),
                getBoolean("showSkipHud", true),
                getInt("skipVoteRatio", 100),
                getBoolean("debugLogging", false),
                getInt("triggerPollIntervalLocation", 20),
                getInt("triggerPollIntervalBiome", 40),
                getInt("triggerPollIntervalInventory", 20),
                getInt("triggerPollIntervalStructure", 20),
                getInt("triggerPollIntervalGamestage", 20),
                getBoolean("editorEnabled", true)
        );
    }

    @Override
    public void setInt(String key, int value) {
        root.add(key, new JsonPrimitive(value));
        save();
    }

    @Override
    public void setBoolean(String key, boolean value) {
        root.add(key, new JsonPrimitive(value));
        save();
    }

    private int getInt(String key, int defaultValue) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            return root.get(key).getAsInt();
        }
        return defaultValue;
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            return root.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    private void saveDefaults() {
        root.addProperty("skipHoldThresholdMs", 3000);
        root.addProperty("showSkipHud", true);
        root.addProperty("skipVoteRatio", 100);
        root.addProperty("debugLogging", false);
        root.addProperty("triggerPollIntervalLocation", 20);
        root.addProperty("triggerPollIntervalBiome", 40);
        root.addProperty("triggerPollIntervalInventory", 20);
        root.addProperty("triggerPollIntervalStructure", 20);
        root.addProperty("triggerPollIntervalGamestage", 20);
        root.addProperty("editorEnabled", true);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (IOException e) {
            // 配置写入失败 → 改动本次不生效，作者该知道（WARN 可见）
            org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/Config")
                    .warn("配置写入失败: {} ({})", e.getMessage(), CONFIG_PATH);
        }
    }
}
