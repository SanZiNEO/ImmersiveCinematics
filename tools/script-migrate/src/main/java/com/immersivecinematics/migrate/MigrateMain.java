package com.immersivecinematics.migrate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 旧脚本 → 新对象格式的一次性迁移 CLI。
 *
 * <pre>
 * --root &lt;dir&gt;         脚本根目录（默认 cinematics）
 * --out &lt;dir&gt;          输出目录（不覆盖原文件）
 * --dry-run            只分析 + 报告，不写盘
 * --check              对写出的数值对象做重采样自检
 * --apply              原地写回（写前备份）
 * --backup-dir &lt;dir&gt;   备份目录（默认 .migration-backup/&lt;yyyyMMdd-HHmmss&gt;）
 * --report &lt;file&gt;      报告路径（Markdown；同名 .json 一并写出）
 * </pre>
 *
 * <p>退出码：0 = 全部成功；1 = 有未处理错误 / 自检超差；2 = 参数或结构错误。</p>
 */
public final class MigrateMain {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Args args;
    private final Report report;
    private final Schema schema = new Schema();
    private final ObjectMapper objectMapper;
    private final CurveMapper curveMapper;
    private final RuleMapper ruleMapper;

    private MigrateMain(Args args) {
        this.args = args;
        String mode = args.dryRun ? "dry-run" : (args.out != null ? "out" : "apply");
        this.report = new Report(args.root.toAbsolutePath().normalize().toString(), mode);
        this.objectMapper = new ObjectMapper(schema, report);
        this.curveMapper = new CurveMapper(report);
        this.ruleMapper = new RuleMapper(report);
    }

    public static void main(String[] argv) {
        Args parsed = Args.parse(argv);
        if (parsed == null) {
            usage();
            System.exit(2);
            return;
        }
        int code;
        try {
            code = new MigrateMain(parsed).run();
        } catch (Exception e) {
            System.err.println("迁移失败: " + e);
            e.printStackTrace(System.err);
            code = 2;
        }
        System.exit(code);
    }

    private static void usage() {
        System.err.println("""
                用法: script-migrate [--root <dir>] [--out <dir>] [--dry-run] [--check] [--apply]
                                     [--backup-dir <dir>] [--report <file>]
                退出码: 0=成功, 1=有错误/自检超差, 2=参数或结构错误""");
    }

    private int run() throws IOException {
        Path rootAbs = args.root.toAbsolutePath().normalize();
        if (!Files.isDirectory(rootAbs)) {
            System.err.println("--root 不是目录: " + rootAbs);
            return 2;
        }
        Path outAbs = args.out != null ? args.out.toAbsolutePath().normalize() : null;
        if (outAbs != null && outAbs.equals(rootAbs)) {
            System.err.println("--out 不能等于 --root（会原地覆盖）");
            return 2;
        }
        boolean write = !args.dryRun && (outAbs != null || args.apply);
        if (!args.dryRun && outAbs == null && !args.apply) {
            // 未指定写模式：安全回落 dry-run
            args.dryRun = true;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(rootAbs)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .toList();
        }
        int structural = 0;
        for (Path file : files) {
            String relative = rootAbs.relativize(file).toString().replace('\\', '/');
            Report.ScriptStats stats = report.script(relative);
            try {
                stats.bytes = Files.size(file);
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    stats.error = true;
                    report.error(relative + ": 顶层不是 JSON 对象");
                    continue;
                }
                JsonObject root = parsed.getAsJsonObject();
                int result = migrate(root, stats, relative);
                if (result == 2) {
                    structural = 2;
                }
                if (write && !stats.error) {
                    String json = GSON.toJson(root) + "\n";
                    stats.bytes = json.getBytes(StandardCharsets.UTF_8).length;
                    writeFile(file, rootAbs, outAbs, json);
                }
            } catch (Exception e) {
                stats.error = true;
                report.error(relative + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (args.report != null) {
            report.write(args.report);
        }
        if (structural == 2) {
            return 2;
        }
        return report.hasErrors() ? 1 : 0;
    }

    private int migrate(JsonObject root, Report.ScriptStats stats, String relative) {
        stats.newFormat = true;

        JsonObject meta = root.getAsJsonObject("meta");
        if (meta == null) {
            stats.error = true;
            report.error(relative + ": meta 缺失");
            return 1;
        }
        stats.id = ObjectMapper.str(meta, "id", "");
        convertHud(meta, relative);
        reportLegacyDefects(meta, relative);
        if (meta.has("version")) {
            meta.remove("version");
            report.semantic("meta.version 已删除（新格式不再保留）");
        }

        JsonObject timeline = root.getAsJsonObject("timeline");
        if (timeline == null || !timeline.has("tracks") || !timeline.get("tracks").isJsonArray()) {
            stats.error = true;
            report.error(relative + ": timeline.tracks 缺失或不是数组");
            return 1;
        }

        JsonArray tracks = timeline.getAsJsonArray("tracks");
        Set<String> usedIds = new HashSet<>();

        // 预分配所有旧片段 id：goto_clip 的静态解析需要目标片段 id（目标可能尚未轮到处理）。
        for (int ti = 0; ti < tracks.size(); ti++) {
            JsonElement trackEl = tracks.get(ti);
            if (!trackEl.isJsonObject()) {
                continue;
            }
            JsonElement clipsEl = trackEl.getAsJsonObject().get("clips");
            if (clipsEl == null || !clipsEl.isJsonArray()) {
                continue;
            }
            JsonArray clips = clipsEl.getAsJsonArray();
            for (int ci = 0; ci < clips.size(); ci++) {
                JsonElement clipEl = clips.get(ci);
                if (!clipEl.isJsonObject()) {
                    continue;
                }
                JsonObject clip = clipEl.getAsJsonObject();
                boolean hasObjects = clip.has("objects") && clip.get("objects").isJsonObject();
                boolean hasKeyframes = clip.has("keyframes");
                if (hasKeyframes && !hasObjects) {
                    clipId(clip, ti, ci, usedIds, relative);
                }
            }
        }

        double maxEnd = 0;
        boolean infinite = false;

        for (int ti = 0; ti < tracks.size(); ti++) {
            JsonElement trackEl = tracks.get(ti);
            if (!trackEl.isJsonObject()) {
                stats.error = true;
                report.error(relative + " track[" + ti + "]: 不是对象");
                continue;
            }
            JsonObject track = trackEl.getAsJsonObject();
            TrackTypeId type = schema.type(ObjectMapper.str(track, "type", ""));
            JsonElement clipsEl = track.get("clips");
            if (clipsEl == null || !clipsEl.isJsonArray()) {
                continue;
            }
            JsonArray clips = clipsEl.getAsJsonArray();
            for (int ci = 0; ci < clips.size(); ci++) {
                JsonElement clipEl = clips.get(ci);
                if (!clipEl.isJsonObject()) {
                    stats.error = true;
                    report.error(relative + " track[" + ti + "].clips[" + ci + "]: 不是对象");
                    continue;
                }
                JsonObject clip = clipEl.getAsJsonObject();
                stats.clips++;

                double start = number(clip, "start_time", 0);
                double duration = number(clip, "duration", 0);
                if (duration < 0) {
                    infinite = true;
                } else {
                    maxEnd = Math.max(maxEnd, start + duration);
                }

                boolean hasObjects = clip.has("objects") && clip.get("objects").isJsonObject();
                boolean hasKeyframes = clip.has("keyframes");
                if (hasObjects && hasKeyframes) {
                    stats.error = true;
                    report.error(relative + " track[" + ti + "].clips[" + ci
                            + "]: 同时含 objects 与 keyframes（结构错误）");
                    return 2;
                }
                if (hasObjects || !hasKeyframes) {
                    continue;
                }

                stats.newFormat = false;
                JsonElement keyframesEl = clip.get("keyframes");
                if (!keyframesEl.isJsonArray()) {
                    stats.error = true;
                    report.error(relative + " track[" + ti + "].clips[" + ci
                            + "]: keyframes 不是数组");
                    continue;
                }
                List<JsonObject> kfs = new ArrayList<>();
                for (JsonElement kfEl : keyframesEl.getAsJsonArray()) {
                    if (kfEl.isJsonObject()) {
                        kfs.add(kfEl.getAsJsonObject());
                    }
                }
                if (!isStrictlyIncreasing(kfs)) {
                    kfs.sort(Comparator.comparingDouble(ObjectMapper::time));
                    report.manual(relative + " track[" + ti + "].clips[" + ci
                            + "]: 关键帧时间非严格递增，已排序");
                }
                stats.legacyKeyframes += kfs.size();

                String clipId = ObjectMapper.str(clip, "id", null);
                if (clipId == null) {
                    clipId = clipId(clip, ti, ci, usedIds, relative);
                }

                Set<String> channels = ObjectMapper.channels(kfs);
                for (String channel : channels) {
                    int missing = 0;
                    for (JsonObject kf : kfs) {
                        if (!kf.has(channel)) {
                            missing++;
                        }
                    }
                    stats.filledFrames += missing;
                    if (missing > 0) {
                        report.sparse(relative + " " + clipId + ": 通道 '" + channel + "' 缺失 "
                                + missing + "/" + kfs.size() + " 帧，按 schema 默认值补齐");
                    }
                }

                JsonObject objects = objectMapper.map(type, clip, kfs, relative, clipId);

                JsonElement curve = clip.remove("curve");
                if (curve != null && !curve.isJsonNull()) {
                    stats.pathEntries += curveMapper.apply(root, clipId, kfs, curve, relative);
                }
                JsonElement rules = clip.remove("rules");
                if (rules != null && !rules.isJsonNull()) {
                    if (rules.isJsonArray()) {
                        int[] counts = ruleMapper.apply(root, clipId, rules.getAsJsonArray(), relative, usedIds);
                        stats.ruleDefs += counts[0];
                        stats.ruleBindings += counts[1];
                    } else {
                        report.error(relative + " " + clipId + ": rules 不是数组，跳过");
                    }
                }

                clip.remove("keyframes");
                clip.add("objects", objects);
                stats.objects += objects.size();

                if (args.check) {
                    double error = selfCheck(objects, relative, clipId);
                    stats.maxError = Math.max(stats.maxError, error);
                }
            }
        }

        if (timeline.has("total_duration")) {
            double total = number(timeline, "total_duration", 0);
            if (total >= 0 && !infinite && Math.abs(total - maxEnd) > 1e-6) {
                report.duration(relative + ": total_duration=" + total + "，派生末端=" + maxEnd);
            }
            timeline.remove("total_duration");
            report.semantic("timeline.total_duration 已删除（新格式由派生末端决定）");
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // meta 旧入口
    // ------------------------------------------------------------------

    /** 既有夹具缺陷：只记录现状，不修夹具、不改 meta 其它字段。 */
    private void reportLegacyDefects(JsonObject meta, String relative) {
        if (!meta.has("author") || meta.get("author").isJsonNull()) {
            report.manual(relative + ": meta.author 缺失（既有夹具缺陷，未修）");
        }
        String id = ObjectMapper.str(meta, "id", "");
        if (id.length() > 32) {
            report.manual(relative + ": meta.id 长度 " + id.length() + " > 32（既有夹具缺陷，未修）");
        }
        JsonElement version = meta.get("version");
        if (version != null && version.isJsonPrimitive()
                && version.getAsJsonPrimitive().isNumber() && version.getAsInt() != 3) {
            report.manual(relative + ": meta.version=" + version.getAsInt()
                    + "（既有夹具缺陷；字段已删除，未改其它 meta）");
        }
        JsonElement triggersEl = meta.get("triggers");
        if (triggersEl != null && triggersEl.isJsonArray()) {
            JsonArray triggers = triggersEl.getAsJsonArray();
            for (int i = 0; i < triggers.size(); i++) {
                if (!triggers.get(i).isJsonObject()) {
                    continue;
                }
                JsonElement conditionsEl = triggers.get(i).getAsJsonObject().get("conditions");
                if (conditionsEl == null || !conditionsEl.isJsonObject()) {
                    continue;
                }
                JsonObject conditions = conditionsEl.getAsJsonObject();
                for (String key : List.of("entity", "item")) {
                    JsonElement value = conditions.get(key);
                    if (value != null && !value.isJsonNull()
                            && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())) {
                        report.manual(relative + ": triggers[" + i + "].conditions." + key
                                + " 不是字符串（既有夹具缺陷，未修）");
                    }
                }
            }
        }
    }

    private void convertHud(JsonObject meta, String relative) {
        JsonElement hudEl = meta.get("hud_layers");
        if (hudEl != null && !hudEl.isJsonNull()) {
            if (!hudEl.isJsonObject()) {
                report.error(relative + ": meta.hud_layers 不是对象，已删除");
                meta.remove("hud_layers");
            } else {
                JsonObject hud = hudEl.getAsJsonObject();
                JsonObject screen = meta.has("screen_layers") && meta.get("screen_layers").isJsonObject()
                        ? meta.getAsJsonObject("screen_layers") : new JsonObject();
                for (Map.Entry<String, JsonElement> entry : hud.entrySet()) {
                    String key = entry.getKey();
                    boolean hide = entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isBoolean()
                            && entry.getValue().getAsBoolean();
                    if (!screen.has(key)) {
                        screen.add(key, new JsonPrimitive(!hide));
                        report.entry(relative + ": hud_layers['" + key + "']=" + hide
                                + " → screen_layers['" + key + "']=" + (!hide) + "（极性翻转）");
                    } else {
                        report.entry(relative + ": hud_layers['" + key
                                + "'] 与已有 screen_layers 冲突，保留 screen_layers");
                    }
                }
                meta.remove("hud_layers");
                meta.add("screen_layers", screen);
            }
        }

        JsonElement hardEl = meta.get("hard_hide_hud");
        if (hardEl != null && !hardEl.isJsonNull()) {
            boolean hard = hardEl.isJsonPrimitive()
                    && hardEl.getAsJsonPrimitive().isBoolean()
                    && hardEl.getAsBoolean();
            if (!meta.has("screen_layer_mode")) {
                meta.addProperty("screen_layer_mode", hard ? "allowlist" : "normal");
            }
            meta.remove("hard_hide_hud");
            report.entry(relative + ": hard_hide_hud=" + hard + " → screen_layer_mode="
                    + (hard ? "allowlist" : "normal"));
        }
    }

    // ------------------------------------------------------------------
    // id / 自检 / 写盘
    // ------------------------------------------------------------------

    private String clipId(JsonObject clip, int trackIndex, int clipIndex, Set<String> used,
                          String relative) {
        String existing = ObjectMapper.str(clip, "id", null);
        if (existing != null && existing.matches("^[A-Za-z0-9_]{1,64}$") && used.add(existing)) {
            return existing;
        }
        String base = "c" + (trackIndex + 1) + "_" + (clipIndex + 1);
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + n++;
        }
        clip.addProperty("id", candidate);
        report.manual(relative + ": 片段 id '" + (existing == null ? "" : existing)
                + "' 缺失/非法/重复，分配 " + candidate);
        return candidate;
    }

    private double selfCheck(JsonObject objects, String relative, String clipId) {
        double globalMax = 0;
        for (ObjectMapper.Check check : objectMapper.checks()) {
            JsonElement objectEl = objects.get(check.path);
            if (objectEl == null || !objectEl.isJsonObject()) {
                continue;
            }
            JsonObject object = objectEl.getAsJsonObject();
            List<Double> times = new ArrayList<>();
            for (JsonObject kf : check.kfs) {
                times.add(ObjectMapper.time(kf));
            }
            if (times.size() < 2) {
                continue;
            }
            Object zero = schema.zeroOf(check.type, check.path);
            double fallback = zero instanceof Number number ? number.doubleValue() : 0;
            List<Double> old = new ArrayList<>();
            for (JsonElement raw : check.raw) {
                double value = fallback;
                if (raw != null && !raw.isJsonNull()) {
                    try {
                        value = raw.getAsDouble();
                    } catch (RuntimeException ignored) {
                        value = fallback;
                    }
                }
                old.add(value);
            }
            List<Double> sampleTimes = new ArrayList<>(times);
            for (int i = 0; i < times.size() - 1; i++) {
                sampleTimes.add((times.get(i) + times.get(i + 1)) / 2.0);
            }
            Interp interp = schema.interpOf(check.type, check.path);
            double localMax = 0;
            for (double t : sampleTimes) {
                double oldValue = interpolateOld(times, old, t);
                double newValue = sampleNew(object, t, interp);
                localMax = Math.max(localMax, Math.abs(oldValue - newValue));
            }
            double tolerance = check.path.contains("yaw") || check.path.contains("pitch")
                    || check.path.contains("roll") ? 0.01
                    : (check.path.startsWith("position.") ? 1e-3 : 1e-4);
            if (localMax > tolerance) {
                report.manual(relative + " " + clipId + ": 自检超差 " + check.path
                        + " max=" + localMax + " tol=" + tolerance);
            }
            globalMax = Math.max(globalMax, localMax);
        }
        return globalMax;
    }

    private static double interpolateOld(List<Double> times, List<Double> values, double t) {
        int n = times.size();
        if (t <= times.get(0)) {
            return values.get(0);
        }
        if (t >= times.get(n - 1)) {
            return values.get(n - 1);
        }
        for (int i = 0; i < n - 1; i++) {
            if (t <= times.get(i + 1)) {
                double span = times.get(i + 1) - times.get(i);
                if (span <= 0) {
                    return values.get(i + 1);
                }
                double s = (t - times.get(i)) / span;
                return values.get(i) + (values.get(i + 1) - values.get(i)) * s;
            }
        }
        return values.get(n - 1);
    }

    private static double sampleNew(JsonObject object, double t, Interp interp) {
        JsonElement valueEl = object.get("value");
        if (valueEl != null && !object.has("keyframes")) {
            return numeric(valueEl, 0);
        }
        JsonElement framesEl = object.get("keyframes");
        if (framesEl == null || !framesEl.isJsonArray() || framesEl.getAsJsonArray().isEmpty()) {
            return 0;
        }
        JsonArray frames = framesEl.getAsJsonArray();
        int n = frames.size();
        double[] times = new double[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            JsonObject frame = frames.get(i).getAsJsonObject();
            times[i] = number(frame, "time", 0);
            values[i] = numeric(frame.get("value"), 0);
        }
        if (t <= times[0]) {
            return values[0];
        }
        if (t >= times[n - 1]) {
            return values[n - 1];
        }
        for (int i = 0; i < n - 1; i++) {
            if (t <= times[i + 1]) {
                double span = times[i + 1] - times[i];
                if (span <= 0) {
                    return values[i + 1];
                }
                double s = (t - times[i]) / span;
                double a = values[i];
                double b = values[i + 1];
                return switch (interp) {
                    case LINEAR -> a + (b - a) * s;
                    case ANGLE -> {
                        double d = b - a;
                        while (d > 180) {
                            d -= 360;
                        }
                        while (d < -180) {
                            d += 360;
                        }
                        yield a + d * s;
                    }
                    case GEOMETRIC -> a > 0 && b > 0
                            ? Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * s)
                            : a + (b - a) * s;
                    case STEP -> s < 1 ? a : b;
                    case NONE -> a;
                };
            }
        }
        return values[n - 1];
    }

    private static double numeric(JsonElement e, double fallback) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()
                || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return e.getAsDouble();
    }

    private void writeFile(Path source, Path rootAbs, Path outAbs, String json) throws IOException {
        if (outAbs != null) {
            Path target = outAbs.resolve(rootAbs.relativize(source.toAbsolutePath().normalize()));
            Report.writeText(target, json);
            return;
        }
        if (!args.apply) {
            return;
        }
        Path backupDir = args.backupDir != null
                ? args.backupDir.toAbsolutePath().normalize()
                : Path.of(".migration-backup").toAbsolutePath().normalize()
                        .resolve(LocalDateTime.now().format(STAMP));
        report.backup(source, rootAbs, backupDir);
        Files.writeString(source, json, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean isStrictlyIncreasing(List<JsonObject> kfs) {
        for (int i = 1; i < kfs.size(); i++) {
            if (ObjectMapper.time(kfs.get(i)) <= ObjectMapper.time(kfs.get(i - 1))) {
                return false;
            }
        }
        return true;
    }

    private static double number(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    private static final class Args {
        Path root = Path.of("cinematics");
        Path out;
        boolean dryRun;
        boolean check;
        boolean apply;
        Path backupDir;
        Path report;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "--root" -> {
                        if (++i >= argv.length) {
                            return null;
                        }
                        a.root = Path.of(argv[i]);
                    }
                    case "--out" -> {
                        if (++i >= argv.length) {
                            return null;
                        }
                        a.out = Path.of(argv[i]);
                    }
                    case "--backup-dir" -> {
                        if (++i >= argv.length) {
                            return null;
                        }
                        a.backupDir = Path.of(argv[i]);
                    }
                    case "--report" -> {
                        if (++i >= argv.length) {
                            return null;
                        }
                        a.report = Path.of(argv[i]);
                    }
                    case "--dry-run" -> a.dryRun = true;
                    case "--check" -> a.check = true;
                    case "--apply" -> a.apply = true;
                    default -> {
                        System.err.println("未知参数: " + arg);
                        return null;
                    }
                }
            }
            if (a.dryRun && a.apply) {
                System.err.println("--dry-run 与 --apply 不能同时使用");
                return null;
            }
            return a;
        }
    }
}
