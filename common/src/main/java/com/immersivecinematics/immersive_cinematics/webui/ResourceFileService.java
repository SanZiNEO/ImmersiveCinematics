package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.util.LangResources;
import com.immersivecinematics.immersive_cinematics.util.ResourcePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 资源目录服务：WebUI {@code resource.list} 的只读列举（文本 / 图片 / 音频）。
 * <p>
 * 列举范围 = {@code <游戏目录>/immersive_cinematics/resource/}（{@link ResourcePath}），
 * 与运行时读取同一份本地资源——编辑器不需要（也不该）走服务器流量。
 * <p>
 * <b>路径安全</b>：所有相对路径经 {@link #resolveSafe} 归一化 + 包含性校验，
 * 拒绝绝对路径 / 盘符 / {@code ..} 逃逸（与 {@code ScriptFileService.resolveSafe} 同款防护）；
 * 语言代码另按严格小写约束过滤，双重保证文件名不会穿越目录。
 * <p>
 * 本类不写文件：编辑器改译文请直接编辑 {@code resource/lang/*.json}（key × 语言矩阵面板后置，§7-3）。
 */
public final class ResourceFileService {

    /** 文本资源：{@code lang/*.json} 的 key 清单（默认扫描 {@code lang/}） */
    public static final String KIND_LANG = "lang";
    /** 图片资源：{@code png} / {@code gif}（OVERLAY image 的 {@code path}） */
    public static final String KIND_IMAGE = "image";
    /** 音频资源：{@code ogg} / {@code wav}（AUDIO 轨的 {@code sound}，{@code source="file"}） */
    public static final String KIND_AUDIO = "audio";
    /** 全部文件（不按扩展名过滤） */
    public static final String KIND_ALL = "all";

    private static final Set<String> KINDS = Set.of(KIND_LANG, KIND_IMAGE, KIND_AUDIO, KIND_ALL);
    private static final Set<String> IMAGE_EXT = Set.of("png", "gif");
    private static final Set<String> AUDIO_EXT = Set.of("ogg", "wav");
    /** 与 {@code ScriptFileService.listScripts} 同款深度上限 */
    private static final int MAX_DEPTH = 5;
    /** 列举条数上限（病态目录树兜底） */
    private static final int MAX_FILES = 2000;
    /** 语言文件名 = 语言代码：严格小写（plans/0.3.6/script-i18n.md §6-2） */
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z0-9_]{2,16}$");

    private ResourceFileService() {}

    /**
     * 列举资源（客户端绑定：{@code <游戏目录>/immersive_cinematics/resource/}）。
     *
     * @param kind {@code lang} / {@code image} / {@code audio} / {@code all}（空 = {@code all}）
     * @param dir  可选：资源根下的子目录（如 {@code "intro"}），空 = 整棵树（{@code lang} 默认 {@code "lang"}）
     * @return 响应载荷：{@code {kind, dir, languages[], entries[]}}（lang）或 {@code {kind, dir, files[]}}
     * @throws IOException kind 非法或 {@code dir} 越界
     */
    public static JsonObject list(String kind, String dir) throws IOException {
        return list(ResourcePath.getBasePath(), kind, dir);
    }

    /**
     * 列举资源（给定资源根目录；不依赖客户端状态，便于自检 / 复用）。
     *
     * @throws IOException kind 非法或 {@code dir} 越界
     */
    public static JsonObject list(Path resourceRoot, String kind, String dir) throws IOException {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) k = KIND_ALL;
        if (!KINDS.contains(k)) throw new IOException("unknown resource kind: " + kind);

        Path root = resourceRoot.toAbsolutePath().normalize();
        String rel = normalizeRelative(dir);
        // lang 默认只看 lang/ 子目录；显式给了 dir 就用 dir
        if (rel.isEmpty() && KIND_LANG.equals(k)) rel = LangResources.LANG_DIR;
        Path scope = resolveSafe(root, rel);

        JsonObject out = new JsonObject();
        out.addProperty("kind", k);
        out.addProperty("dir", rel);
        if (KIND_LANG.equals(k)) {
            for (Map.Entry<String, JsonElement> entry : listLang(scope).entrySet()) {
                out.add(entry.getKey(), entry.getValue());
            }
        } else {
            out.add("files", listFiles(root, scope, k));
        }
        return out;
    }

    // ========== 列举实现 ==========

    /** 语言文件列举：{@code languages} = 现有语言代码，{@code entries} = key 并集 + 各语言译文 */
    private static JsonObject listLang(Path scope) throws IOException {
        Map<String, Map<String, String>> dictionaries = new TreeMap<>();
        if (Files.isDirectory(scope)) {
            try (Stream<Path> files = Files.list(scope)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    String language = name.substring(0, name.length() - ".json".length());
                    if (!LANGUAGE_PATTERN.matcher(language).matches()) continue;
                    dictionaries.put(language, LangResources.readDictionary(file));
                }
            }
        }

        JsonObject out = new JsonObject();
        JsonArray languages = new JsonArray();
        for (String language : dictionaries.keySet()) languages.add(language);
        out.add("languages", languages);

        Set<String> keys = new TreeSet<>();
        for (Map<String, String> dict : dictionaries.values()) keys.addAll(dict.keySet());
        JsonArray entries = new JsonArray();
        for (String key : keys) {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", key);
            JsonObject values = new JsonObject();
            for (Map.Entry<String, Map<String, String>> dict : dictionaries.entrySet()) {
                String text = dict.getValue().get(key);
                if (text != null) values.addProperty(dict.getKey(), text);
            }
            entry.add("values", values);
            entries.add(entry);
        }
        out.add("entries", entries);
        return out;
    }

    /** 文件列举：相对资源根的路径（与 {@code path} / {@code sound} 字段口径一致），按扩展名过滤 */
    private static JsonArray listFiles(Path root, Path scope, String kind) throws IOException {
        JsonArray files = new JsonArray();
        if (!Files.isDirectory(scope)) return files;
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(scope, MAX_DEPTH)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (found.size() >= MAX_FILES) break;
                if (!matchesKind(file, kind)) continue;
                found.add(root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'));
            }
        }
        found.sort(String::compareTo);
        for (String f : found) files.add(f);
        return files;
    }

    private static boolean matchesKind(Path file, String kind) {
        if (KIND_ALL.equals(kind)) return true;
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return (KIND_IMAGE.equals(kind) ? IMAGE_EXT : AUDIO_EXT).contains(ext);
    }

    // ========== 路径安全 ==========

    /**
     * 相对路径归一化：统一分隔符、去掉首尾空白与 {@code ./} 前缀。
     * （真正的越界判定在 {@link #resolveSafe}。）
     */
    public static String normalizeRelative(String relative) {
        if (relative == null) return "";
        String rel = relative.trim().replace('\\', '/');
        while (rel.startsWith("./")) rel = rel.substring(2);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel.equals(".") ? "" : rel;
    }

    /**
     * 解析资源根下的相对路径：拒绝绝对路径 / 盘符 / UNC，拒绝 {@code ..} 逃出资源根。
     *
     * @throws IOException 路径越界（编辑器侧表现为 {@code resource.list} 报错，不落任何文件）
     */
    public static Path resolveSafe(Path root, String relative) throws IOException {
        String rel = normalizeRelative(relative);
        if (rel.isEmpty()) return root;
        if (rel.startsWith("/") || rel.contains(":") || Path.of(rel).isAbsolute()) {
            throw new IOException("invalid resource path: " + relative);
        }
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("invalid resource path: " + relative);
        }
        return target;
    }
}
