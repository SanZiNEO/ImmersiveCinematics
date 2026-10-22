package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.script.ScriptTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 脚本文件服务：WebUI 的 {@code script.list/load/save/delete/new} 共用同一套文件 IO。
 *
 * 所有路径都是相对 {@code immersive_cinematics/scripts/} 的路径（{@code /} 分隔）；
 * 越界路径统一抛 {@link IOException}，不落盘。
 */
public final class ScriptFileService {

    private static final Path SCRIPTS_DIR = Paths.get("immersive_cinematics", "scripts");

    private ScriptFileService() {
    }

    /**
     * 递归列举 scripts 根下的 {@code .json} 文件（相对路径，{@code /} 分隔，字典序）。
     *
     * @return 相对路径列表；根目录不存在或不可读时为空表（不抛错）
     */
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

    /**
     * 读取相对路径脚本的原始 JSON 文本。
     *
     * @throws IOException 文件不可读，或路径越出 scripts 根
     */
    public static String loadScript(String relativePath) throws IOException {
        Path path = resolveSafe(relativePath);
        return Files.readString(path);
    }

    /**
     * 覆盖写入相对路径脚本，父目录按需创建。
     *
     * @throws IOException 写入失败，或路径越出 scripts 根
     */
    public static void saveScript(String relativePath, String json) throws IOException {
        Path path = resolveSafe(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, json);
    }

    /**
     * 删除相对路径脚本（不存在时静默成功）。
     *
     * @throws IOException 删除失败，或路径越出 scripts 根
     */
    public static void deleteScript(String relativePath) throws IOException {
        Path path = resolveSafe(relativePath);
        Files.deleteIfExists(path);
    }

    /** 新建脚本的初始 JSON 文本（模板见 {@link ScriptTemplate}）。 */
    public static String newScriptJson() {
        return ScriptTemplate.newScriptJson();
    }

    /** 归一化后校验目标仍落在 scripts 根内，挡掉 {@code ../} 逃逸。 */
    private static Path resolveSafe(String relativePath) throws IOException {
        Path base = SCRIPTS_DIR.toAbsolutePath().normalize();
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base)) {
            throw new IOException("invalid script path: " + relativePath);
        }
        return target;
    }
}
