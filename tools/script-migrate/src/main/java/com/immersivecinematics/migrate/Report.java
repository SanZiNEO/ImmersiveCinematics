package com.immersivecinematics.migrate;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 迁移报告 + 备份 / 写盘 / 幂等判定。
 *
 * <p>报告分节固定：稀疏补齐、total_duration vs 派生末端、旧入口 → 新入口、
 * 明确语义变更、人工复核、未知路径、错误。</p>
 */
public final class Report {

    /** 单脚本统计。 */
    public static final class ScriptStats {
        public final String path;
        public String id = "";
        public int clips;
        public int legacyKeyframes;
        public int objects;
        public int filledFrames;
        public int pathEntries;
        public int ruleDefs;
        public int ruleBindings;
        public double maxError;
        public long bytes;
        public boolean newFormat;
        public boolean error;
        public String note = "";

        ScriptStats(String path) {
            this.path = path;
        }
    }

    private final String generatedAt = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private final String root;
    private final String mode;
    private final List<ScriptStats> scripts = new ArrayList<>();
    private final Set<String> sparse = new LinkedHashSet<>();
    private final Set<String> duration = new LinkedHashSet<>();
    private final Set<String> entryMapping = new LinkedHashSet<>();
    private final Set<String> semanticChanges = new LinkedHashSet<>();
    private final Set<String> manualReview = new LinkedHashSet<>();
    private final Set<String> unknownPaths = new LinkedHashSet<>();
    private final Set<String> errors = new LinkedHashSet<>();

    public Report(String root, String mode) {
        this.root = root;
        this.mode = mode;
    }

    public ScriptStats script(String path) {
        ScriptStats s = new ScriptStats(path);
        scripts.add(s);
        return s;
    }

    public void sparse(String line) {
        sparse.add(line);
    }

    public void duration(String line) {
        duration.add(line);
    }

    public void entry(String line) {
        entryMapping.add(line);
    }

    public void semantic(String line) {
        semanticChanges.add(line);
    }

    public void manual(String line) {
        manualReview.add(line);
    }

    public void unknown(String line) {
        unknownPaths.add(line);
    }

    public void error(String line) {
        errors.add(line);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public int totalScripts() {
        return scripts.size();
    }

    public int newFormatCount() {
        int n = 0;
        for (ScriptStats s : scripts) {
            if (s.newFormat) {
                n++;
            }
        }
        return n;
    }

    public int migratedCount() {
        int n = 0;
        for (ScriptStats s : scripts) {
            if (!s.newFormat && !s.error) {
                n++;
            }
        }
        return n;
    }

    public int errorCount() {
        int n = 0;
        for (ScriptStats s : scripts) {
            if (s.error) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 备份 / 写盘
    // ------------------------------------------------------------------

    /**
     * 把 {@code source} 复制到 {@code backupDir} 下保留相对路径。
     *
     * @return 备份文件路径
     */
    public Path backup(Path source, Path rootDir, Path backupDir) throws IOException {
        Path relative = rootDir.relativize(source);
        Path target = backupDir.resolve(relative);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** 写文本文件（自动建父目录）。 */
    public static void writeText(Path file, String text) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** 写 Markdown 报告；同目录同名 {@code .json} 报告一并写出。 */
    public void write(Path file) throws IOException {
        writeText(file, markdown());
        Path json = file;
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        json = file.resolveSibling(base + ".json");
        writeText(json, json());
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    public String markdown() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# script-migrate 报告\n\n");
        sb.append("- 生成时间：").append(generatedAt).append('\n');
        sb.append("- 根目录：`").append(root).append("`\n");
        sb.append("- 模式：").append(mode).append('\n');
        sb.append("- 文件：").append(scripts.size())
                .append("（已是新格式 ").append(newFormatCount())
                .append("，迁移 ").append(migratedCount())
                .append("，错误 ").append(errorCount()).append("）\n\n");

        sb.append("## 总览\n\n");
        sb.append("| 脚本 | clip | 旧关键帧 | 对象 | 填充帧 | 路径 | 规则定义 | 规则绑定 | 最大误差 | 体积 | 状态 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (ScriptStats s : scripts) {
            sb.append("| ").append(escape(s.path))
                    .append(" | ").append(s.clips)
                    .append(" | ").append(s.legacyKeyframes)
                    .append(" | ").append(s.objects)
                    .append(" | ").append(s.filledFrames)
                    .append(" | ").append(s.pathEntries)
                    .append(" | ").append(s.ruleDefs)
                    .append(" | ").append(s.ruleBindings)
                    .append(" | ").append(format(s.maxError))
                    .append(" | ").append(s.bytes)
                    .append(" | ").append(s.error ? "错误" : (s.newFormat ? "已是新格式" : "迁移"))
                    .append(" |\n");
        }
        sb.append('\n');

        section(sb, "稀疏补齐", sparse);
        section(sb, "total_duration vs 派生末端", duration);
        section(sb, "旧入口 → 新入口", entryMapping);
        section(sb, "明确语义变更", semanticChanges);
        section(sb, "人工复核", manualReview);
        section(sb, "未知路径（已丢弃）", unknownPaths);
        section(sb, "错误", errors);
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, Set<String> lines) {
        sb.append("## ").append(title).append("\n\n");
        if (lines.isEmpty()) {
            sb.append("（无）\n\n");
            return;
        }
        for (String line : lines) {
            sb.append("- ").append(line).append('\n');
        }
        sb.append('\n');
    }

    public String json() {
        JsonObject root = new JsonObject();
        root.addProperty("generated_at", generatedAt);
        root.addProperty("root", this.root);
        root.addProperty("mode", mode);
        JsonObject totals = new JsonObject();
        totals.addProperty("scripts", scripts.size());
        totals.addProperty("new_format", newFormatCount());
        totals.addProperty("migrated", migratedCount());
        totals.addProperty("errors", errorCount());
        root.add("totals", totals);

        JsonArray arr = new JsonArray();
        for (ScriptStats s : scripts) {
            JsonObject o = new JsonObject();
            o.addProperty("path", s.path);
            o.addProperty("id", s.id);
            o.addProperty("clips", s.clips);
            o.addProperty("legacy_keyframes", s.legacyKeyframes);
            o.addProperty("objects", s.objects);
            o.addProperty("filled_frames", s.filledFrames);
            o.addProperty("path_entries", s.pathEntries);
            o.addProperty("rule_defs", s.ruleDefs);
            o.addProperty("rule_bindings", s.ruleBindings);
            o.addProperty("max_error", s.maxError);
            o.addProperty("bytes", s.bytes);
            o.addProperty("new_format", s.newFormat);
            o.addProperty("error", s.error);
            o.addProperty("note", s.note);
            arr.add(o);
        }
        root.add("scripts", arr);
        root.add("sparse", strings(sparse));
        root.add("duration", strings(duration));
        root.add("entry_mapping", strings(entryMapping));
        root.add("semantic_changes", strings(semanticChanges));
        root.add("manual_review", strings(manualReview));
        root.add("unknown_paths", strings(unknownPaths));
        root.add("errors", strings(errors));
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray arr = new JsonArray();
        for (String v : values) {
            arr.add(v);
        }
        return arr;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    private static String format(double v) {
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }
}
