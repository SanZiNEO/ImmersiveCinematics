package com.example.immersive_cinematics.director;

import com.example.immersive_cinematics.ImmersiveCinematics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 镜头脚本存储管理类
 * 负责处理镜头脚本的加载、保存、热加载和管理
 */
public class CameraScriptStorage {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Vec3.class, new Vec3TypeAdapter())
            .create();

    // 使用 Forge 标准配置路径
    private static final Path CAMERA_SCRIPTS_DIR;
    private static final Path BINDINGS_FILE;

    static {
        // 获取 Forge 配置目录
        Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        CAMERA_SCRIPTS_DIR = configDir.resolve("immersive_cinematics").resolve("camera_scripts");
        BINDINGS_FILE = configDir.resolve("immersive_cinematics").resolve("bindings.json");
        LOGGER.info("Camera scripts directory resolved to: {}", CAMERA_SCRIPTS_DIR.toAbsolutePath());
        LOGGER.info("Bindings file resolved to: {}", BINDINGS_FILE.toAbsolutePath());
    }

    private static CameraScriptStorage instance;
    private final Map<String, CameraScript> cameraScripts;
    private final List<TriggerBinding> bindings;
    private boolean initialized = false;

    private CameraScriptStorage() {
        this.cameraScripts = new HashMap<>();
        this.bindings = new ArrayList<>();
    }

    public static CameraScriptStorage getInstance() {
        if (instance == null) {
            instance = new CameraScriptStorage();
        }
        return instance;
    }

    /**
     * 初始化存储系统
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        LOGGER.info("Initializing camera script storage system...");

        // 打印当前工作目录和配置路径信息
        LOGGER.debug("Current working directory: {}", System.getProperty("user.dir"));
        LOGGER.debug("Camera scripts directory: {}", CAMERA_SCRIPTS_DIR.toAbsolutePath());

        // 创建镜头脚本目录
        try {
            if (!Files.exists(CAMERA_SCRIPTS_DIR)) {
                Files.createDirectories(CAMERA_SCRIPTS_DIR);
                LOGGER.info("Created camera scripts directory: {}", CAMERA_SCRIPTS_DIR);
            } else {
                LOGGER.debug("Camera scripts directory exists: {}", CAMERA_SCRIPTS_DIR);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create camera scripts directory", e);
            return;
        }

        // 加载所有镜头脚本
        loadAllCameraScripts();
        // 加载触发绑定
        loadBindings();

        initialized = true;
        LOGGER.info("Camera script storage system initialized. Loaded {} camera scripts and {} bindings",
                cameraScripts.size(), bindings.size());
        
        // 打印加载的脚本列表
        if (!cameraScripts.isEmpty()) {
            LOGGER.debug("Loaded scripts: {}", cameraScripts.keySet());
        } else {
            LOGGER.warn("No camera scripts loaded");
        }
    }

    /**
     * 加载所有镜头脚本
     */
    private void loadAllCameraScripts() {
        cameraScripts.clear();

        File cameraScriptsDir = CAMERA_SCRIPTS_DIR.toFile();
        LOGGER.debug("Camera scripts directory file: {}", cameraScriptsDir.getAbsolutePath());
        LOGGER.debug("Camera scripts directory exists: {}", cameraScriptsDir.exists());
        LOGGER.debug("Camera scripts directory is directory: {}", cameraScriptsDir.isDirectory());

        if (!cameraScriptsDir.exists() || !cameraScriptsDir.isDirectory()) {
            LOGGER.warn("Camera scripts directory not found or not a directory: {}", cameraScriptsDir.getAbsolutePath());
            return;
        }

        File[] cameraScriptFiles = cameraScriptsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));

        if (cameraScriptFiles == null) {
            LOGGER.warn("Camera scripts directory not found or empty");
            return;
        }

        LOGGER.debug("Found {} script files in camera scripts directory", cameraScriptFiles.length);
        
        for (File cameraScriptFile : cameraScriptFiles) {
            LOGGER.debug("Found script file: {}", cameraScriptFile.getName());
        }

        for (File cameraScriptFile : cameraScriptFiles) {
            try {
                LOGGER.debug("Loading script file: {}", cameraScriptFile.getAbsolutePath());
                String cameraScriptName = cameraScriptFile.getName().substring(0, cameraScriptFile.getName().length() - 5);
                CameraScript cameraScript = loadCameraScript(cameraScriptFile);
                cameraScripts.put(cameraScriptName, cameraScript);
                LOGGER.info("Loaded camera script: {} with {} shot rules", cameraScriptName, cameraScript.getShotRules().size());
            } catch (Exception e) {
                LOGGER.error("Failed to load camera script: {}", cameraScriptFile.getName(), e);
            }
        }
        
        LOGGER.debug("Loaded {} camera scripts total: {}", cameraScripts.size(), cameraScripts.keySet());
    }

    /**
     * 加载单个镜头脚本
     */
    private CameraScript loadCameraScript(File file) throws IOException {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            LOGGER.debug("Reading script file content...");
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            LOGGER.debug("Script file content: {}", content);
            
            CameraScript script = GSON.fromJson(reader, CameraScript.class);
            LOGGER.debug("Successfully parsed script: {}", script.getName());
            return script;
        } catch (Exception e) {
            LOGGER.error("Error loading script file: " + file.getAbsolutePath(), e);
            throw e;
        }
    }

    /**
     * 加载触发绑定
     */
    private void loadBindings() {
        bindings.clear();

        File bindingsFile = BINDINGS_FILE.toFile();
        if (!bindingsFile.exists()) {
            LOGGER.warn("Bindings file not found");
            return;
        }

        try (FileReader reader = new FileReader(bindingsFile, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<TriggerBinding>>() {}.getType();
            List<TriggerBinding> loadedBindings = GSON.fromJson(reader, listType);
            if (loadedBindings != null) {
                bindings.addAll(loadedBindings);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load trigger bindings", e);
        }
    }

    /**
     * 保存镜头脚本到文件
     */
    public void saveCameraScript(String cameraScriptName, CameraScript cameraScript) {
        if (!initialized) {
            initialize();
        }

        try {
            File cameraScriptFile = CAMERA_SCRIPTS_DIR.resolve(cameraScriptName + ".json").toFile();
            try (FileWriter writer = new FileWriter(cameraScriptFile, StandardCharsets.UTF_8)) {
                GSON.toJson(cameraScript, writer);
            }

            cameraScripts.put(cameraScriptName, cameraScript);
            LOGGER.info("Saved camera script: {}", cameraScriptName);
        } catch (IOException e) {
            LOGGER.error("Failed to save camera script: {}", cameraScriptName, e);
        }
    }

    /**
     * 保存触发绑定
     */
    public void saveBindings() {
        if (!initialized) {
            initialize();
        }

        try {
            // 确保配置目录存在
            if (!Files.exists(BINDINGS_FILE.getParent())) {
                Files.createDirectories(BINDINGS_FILE.getParent());
            }

            File bindingsFile = BINDINGS_FILE.toFile();
            try (FileWriter writer = new FileWriter(bindingsFile, StandardCharsets.UTF_8)) {
                GSON.toJson(bindings, writer);
            }

            LOGGER.info("Saved trigger bindings");
        } catch (IOException e) {
            LOGGER.error("Failed to save trigger bindings", e);
        }
    }

    /**
     * 获取所有镜头脚本
     */
    public Map<String, CameraScript> getCameraScripts() {
        if (!initialized) {
            initialize();
        }
        return Collections.unmodifiableMap(cameraScripts);
    }

    /**
     * 获取单个镜头脚本
     */
    public CameraScript getCameraScript(String cameraScriptName) {
        if (!initialized) {
            initialize();
        }
        return cameraScripts.get(cameraScriptName);
    }

    /**
     * 删除镜头脚本
     */
    public boolean deleteCameraScript(String cameraScriptName) {
        if (!initialized) {
            initialize();
        }

        File cameraScriptFile = CAMERA_SCRIPTS_DIR.resolve(cameraScriptName + ".json").toFile();
        if (cameraScriptFile.delete()) {
            cameraScripts.remove(cameraScriptName);
            LOGGER.info("Deleted camera script: {}", cameraScriptName);
            return true;
        }
        LOGGER.warn("Failed to delete camera script file: {}", cameraScriptName);
        return false;
    }

    /**
     * 重命名镜头脚本
     */
    public boolean renameCameraScript(String oldName, String newName) {
        if (!initialized) {
            initialize();
        }

        if (!cameraScripts.containsKey(oldName)) {
            LOGGER.warn("Camera script not found for rename: {}", oldName);
            return false;
        }

        File oldFile = CAMERA_SCRIPTS_DIR.resolve(oldName + ".json").toFile();
        File newFile = CAMERA_SCRIPTS_DIR.resolve(newName + ".json").toFile();

        if (oldFile.renameTo(newFile)) {
            CameraScript cameraScript = cameraScripts.remove(oldName);
            cameraScripts.put(newName, cameraScript);
            LOGGER.info("Renamed camera script: {} -> {}", oldName, newName);
            return true;
        }

        LOGGER.warn("Failed to rename camera script file: {} -> {}", oldName, newName);
        return false;
    }

    /**
     * 获取所有触发绑定
     */
    public List<TriggerBinding> getBindings() {
        if (!initialized) {
            initialize();
        }
        return Collections.unmodifiableList(bindings);
    }

    /**
     * 添加触发绑定
     */
    public void addBinding(TriggerBinding binding) {
        if (!initialized) {
            initialize();
        }

        bindings.add(binding);
        saveBindings();
        LOGGER.info("Added trigger binding: {} -> {}", binding.getTriggerType(), binding.getScriptName());
    }

    /**
     * 删除触发绑定
     */
    public void removeBinding(TriggerBinding binding) {
        if (!initialized) {
            initialize();
        }

        bindings.remove(binding);
        saveBindings();
        LOGGER.info("Removed trigger binding: {} -> {}", binding.getTriggerType(), binding.getScriptName());
    }

    /**
     * 删除指定位置的触发绑定
     */
    public void removeBinding(int index) {
        if (!initialized) {
            initialize();
        }

        if (index >= 0 && index < bindings.size()) {
            TriggerBinding removed = bindings.remove(index);
            saveBindings();
            LOGGER.info("Removed trigger binding: {} -> {}", removed.getTriggerType(), removed.getScriptName());
        }
    }

    /**
     * 热加载所有镜头脚本和绑定
     */
    public void hotReload() {
        LOGGER.info("Hot reloading camera scripts and bindings...");
        loadAllCameraScripts();
        loadBindings();
        LOGGER.info("Hot reload complete. Loaded {} camera scripts and {} bindings",
                cameraScripts.size(), bindings.size());
    }

    /**
     * 热加载单个镜头脚本
     */
    public boolean hotReloadCameraScript(String cameraScriptName) {
        File cameraScriptFile = CAMERA_SCRIPTS_DIR.resolve(cameraScriptName + ".json").toFile();
        if (!cameraScriptFile.exists()) {
            LOGGER.warn("Camera script file not found for hot reload: {}", cameraScriptName);
            return false;
        }

        try {
            CameraScript cameraScript = loadCameraScript(cameraScriptFile);
            cameraScripts.put(cameraScriptName, cameraScript);
            LOGGER.info("Hot reloaded camera script: {}", cameraScriptName);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to hot reload camera script: {}", cameraScriptName, e);
            return false;
        }
    }

    /**
     * 检查镜头脚本文件是否存在
     */
    public boolean cameraScriptExists(String cameraScriptName) {
        if (!initialized) {
            initialize();
        }
        return cameraScripts.containsKey(cameraScriptName);
    }

    /**
     * 兼容性方法：检查镜头脚本文件是否存在（旧方法名）
     */
    public boolean hasScript(String cameraScriptName) {
        return cameraScriptExists(cameraScriptName);
    }

    /**
     * 获取镜头脚本文件路径
     */
    public String getCameraScriptFilePath(String cameraScriptName) {
        return CAMERA_SCRIPTS_DIR.resolve(cameraScriptName + ".json").toString();
    }

    // 以下是兼容性方法，保持对旧代码的支持
    public CameraScript getScript(String cameraScriptName) {
        return getCameraScript(cameraScriptName);
    }

    public void saveScript(String cameraScriptName, CameraScript cameraScript) {
        saveCameraScript(cameraScriptName, cameraScript);
    }

    public boolean deleteScript(String cameraScriptName) {
        return deleteCameraScript(cameraScriptName);
    }

    public Map<String, CameraScript> getAllScripts() {
        return getCameraScripts();
    }

    public Map<String, CameraScript> getScripts() {
        return getCameraScripts();
    }

    /**
     * Vec3类型适配器，用于Gson序列化
     */
    private static class Vec3TypeAdapter extends TypeAdapter<Vec3> {
        @Override
        public void write(JsonWriter out, Vec3 value) throws IOException {
            out.beginObject();
            out.name("x").value(value.x);
            out.name("y").value(value.y);
            out.name("z").value(value.z);
            out.endObject();
        }

        @Override
        public Vec3 read(JsonReader in) throws IOException {
            double x = 0, y = 0, z = 0;

            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "x":
                        x = in.nextDouble();
                        break;
                    case "y":
                        y = in.nextDouble();
                        break;
                    case "z":
                        z = in.nextDouble();
                        break;
                }
            }
            in.endObject();

            return new Vec3(x, y, z);
        }
    }
}