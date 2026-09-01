package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 独立的脚本文件服务，替代原来散落在 EditorScreen 里的文件 IO。
 */
public final class ScriptFileService {

    private static final Path SCRIPTS_DIR = Paths.get("immersive_cinematics", "scripts");

    private ScriptFileService() {
    }

    public static List<String> listScripts() {
        List<String> result = new ArrayList<>();
        if (!Files.exists(SCRIPTS_DIR)) return result;
        try (Stream<Path> files = Files.walk(SCRIPTS_DIR, 5)) {
            result.addAll(files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> SCRIPTS_DIR.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList()));
        } catch (IOException ignored) {
        }
        return result;
    }

    public static String loadScript(String relativePath) throws IOException {
        Path path = resolveSafe(relativePath);
        return Files.readString(path);
    }

    public static void saveScript(String relativePath, String json) throws IOException {
        Path path = resolveSafe(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, json);
    }

    public static void deleteScript(String relativePath) throws IOException {
        Path path = resolveSafe(relativePath);
        Files.deleteIfExists(path);
    }

    public static String newScriptJson() {
        EditorDocument doc = new EditorDocument();
        return doc.toJson();
    }

    private static Path resolveSafe(String relativePath) throws IOException {
        Path base = SCRIPTS_DIR.toAbsolutePath().normalize();
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base)) {
            throw new IOException("invalid script path: " + relativePath);
        }
        return target;
    }
}
