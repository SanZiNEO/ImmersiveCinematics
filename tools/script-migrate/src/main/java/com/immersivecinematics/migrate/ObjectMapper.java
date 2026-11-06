package com.immersivecinematics.migrate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.PropertyMode;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * clip 级字段 + 关键帧通道 → {@code objects}。
 *
 * <p>通道值序列按全局关键帧时间 {@code T} 展开：某帧缺该键 → schema 默认值；
 * 序列全等 → value-only；LINEAR 数值序列做共线压缩。</p>
 */
public final class ObjectMapper {

    /** clip 级保留键（原位，不进 objects）。 */
    public static final Set<String> CLIP_RESERVED = Set.of(
            "start_time", "duration", "loop", "loop_count", "loop_mode", "id", "exit",
            "keyframes", "curve", "rules", "track");

    /** 由 PositionMapper 消费的旧通道。 */
    private static final Set<String> POSITION_CONSUMED = Set.of(
            "position", "position_mode", "facing_origin", "facing_origin_x", "facing_origin_y",
            "facing_origin_z", "facing_origin_time", "follow", "follow_selector", "facing_target");

    private static final Set<String> EVENT_CONSUMED = Set.of("event_type", "command", "position");
    private static final Set<String> MOD_EVENT_CONSUMED = Set.of("event_type", "data");

    /** 自检样本：一个标量旧通道 → 新对象路径。 */
    public static final class Check {
        public final TrackTypeId type;
        public final String path;
        public final List<JsonObject> kfs;
        public final List<JsonElement> raw;

        Check(TrackTypeId type, String path, List<JsonObject> kfs, List<JsonElement> raw) {
            this.type = type;
            this.path = path;
            this.kfs = kfs;
            this.raw = raw;
        }
    }

    private final Schema schema;
    private final Report report;
    private final PositionMapper positionMapper;
    private final List<Check> checks = new ArrayList<>();

    public List<Check> checks() {
        return checks;
    }

    public ObjectMapper(Schema schema, Report report) {
        this.schema = schema;
        this.report = report;
        this.positionMapper = new PositionMapper(schema, report);
    }

    /**
     * 把旧 clip 转成 objects，并就地移除已消费的 clip 级键。
     *
     * @param scriptPath 报告用
     * @param clipId     已分配的片段 id
     */
    public JsonObject map(TrackTypeId type, JsonObject clip, List<JsonObject> kfs,
                          String scriptPath, String clipId) {
        JsonObject objects = new JsonObject();

        // ---- AUDIO：文件循环 loop 走 file_loop，外层不再保留 ----
        if (TrackTypeId.AUDIO.equals(type) && clip.has("loop")) {
            JsonElement loop = clip.remove("loop");
            objects.add("file_loop", value(deep(loop)));
            report.semantic(scriptPath + " " + clipId + ": AUDIO.loop（文件循环）→ objects.file_loop，外层不写 loop");
        }

        // ---- clip 级字段 ----
        String clipEventType = str(clip, "event_type", null);
        JsonElement clipData = clip.get("data");
        String orient = str(clip, "orient", "manual");
        List<String> keys = new ArrayList<>(clip.keySet());
        for (String key : keys) {
            if (CLIP_RESERVED.contains(key)) {
                continue;
            }
            JsonElement raw = clip.get(key);
            if (("yaw_offset".equals(key) || "pitch_offset".equals(key))
                    && !"tangent".equals(orient)) {
                report.manual(scriptPath + " " + clipId + ": 旧 " + key + "=" + raw
                        + " 在 orient=" + orient + " 下运行时不会读取，未写入 objects");
                clip.remove(key);
                continue;
            }
            if (TrackTypeId.EVENT.equals(type) && "event_type".equals(key)) {
                objects.add("event_type", value(deep(raw)));
                clip.remove(key);
                continue;
            }
            if (TrackTypeId.MOD_EVENT.equals(type) && "event_type".equals(key)) {
                objects.add("event_type", value(deep(raw)));
                clip.remove(key);
                continue;
            }
            if (TrackTypeId.MOD_EVENT.equals(type) && "data".equals(key)) {
                objects.add("data", value(deep(raw)));
                clip.remove(key);
                continue;
            }
            if (TrackTypeId.AUDIO.equals(type) && "attenuation".equals(key)
                    && raw != null && raw.isJsonPrimitive() && "inverse".equals(raw.getAsString())) {
                objects.add("attenuation", value(new JsonPrimitive("linear")));
                clip.remove(key);
                report.semantic(scriptPath + " " + clipId + ": attenuation inverse → linear");
                continue;
            }
            putClipValue(objects, type, key, raw, scriptPath, clipId);
            clip.remove(key);
        }

        // ---- 事件轨：关键帧 → 事件记录 ----
        if (TrackTypeId.EVENT.equals(type)) {
            buildEvent(objects, kfs, clipEventType, scriptPath, clipId);
            return objects;
        }
        if (TrackTypeId.MOD_EVENT.equals(type)) {
            buildModEvent(objects, kfs, clipEventType, clipData, scriptPath, clipId);
            return objects;
        }

        // ---- 位置模型（CAMERA）----
        positionMapper.apply(type, kfs, objects, scriptPath, clipId);

        // ---- 其余关键帧通道 ----
        for (String channel : channels(kfs)) {
            if (POSITION_CONSUMED.contains(channel)) {
                continue;
            }
            mapChannel(type, channel, kfs, objects, scriptPath, clipId);
        }
        return objects;
    }

    // ------------------------------------------------------------------
    // clip 级
    // ------------------------------------------------------------------

    private void putClipValue(JsonObject objects, TrackTypeId type, String key, JsonElement raw,
                              String scriptPath, String clipId) {
        if (!schema.exists(type, key)) {
            report.unknown(scriptPath + " | " + clipId + " | " + key + " | " + raw);
            return;
        }
        objects.add(key, value(deep(raw)));
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    private void buildEvent(JsonObject objects, List<JsonObject> kfs, String clipEventType,
                            String scriptPath, String clipId) {
        if (kfs.isEmpty()) {
            return;
        }
        JsonArray frames = new JsonArray();
        List<Double> times = eventTimes(kfs, scriptPath, clipId);
        for (int i = 0; i < kfs.size(); i++) {
            JsonObject kf = kfs.get(i);
            JsonObject record = new JsonObject();
            String eventType = str(kf, "event_type", clipEventType != null ? clipEventType : "command");
            record.addProperty("event_type", eventType);
            record.addProperty("command", str(kf, "command", ""));
            JsonElement pos = kf.get("position");
            if (pos != null && pos.isJsonObject()) {
                record.add("position", deep(pos));
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("time", times.get(i));
            frame.add("value", record);
            frames.add(frame);
            for (String key : kf.keySet()) {
                if (!"time".equals(key) && !EVENT_CONSUMED.contains(key)) {
                    report.manual(scriptPath + " " + clipId + ": EVENT 关键帧字段 '" + key + "' 未进入事件记录");
                }
            }
        }
        JsonObject event = new JsonObject();
        event.add("keyframes", frames);
        objects.add("event", event);
    }

    private void buildModEvent(JsonObject objects, List<JsonObject> kfs, String clipEventType,
                               JsonElement clipData, String scriptPath, String clipId) {
        if (kfs.isEmpty()) {
            return;
        }
        JsonArray frames = new JsonArray();
        List<Double> times = eventTimes(kfs, scriptPath, clipId);
        for (int i = 0; i < kfs.size(); i++) {
            JsonObject kf = kfs.get(i);
            JsonObject record = new JsonObject();
            record.addProperty("event_type", str(kf, "event_type",
                    clipEventType != null ? clipEventType : "mod_event"));
            JsonElement data = kf.get("data");
            if (data != null && !data.isJsonNull()) {
                record.add("data", deep(data));
            } else if (clipData != null && !clipData.isJsonNull()) {
                record.add("data", deep(clipData));
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("time", times.get(i));
            frame.add("value", record);
            frames.add(frame);
            for (String key : kf.keySet()) {
                if (!"time".equals(key) && !MOD_EVENT_CONSUMED.contains(key)) {
                    report.manual(scriptPath + " " + clipId + ": MOD_EVENT 关键帧字段 '" + key + "' 未进入事件记录");
                }
            }
        }
        JsonObject event = new JsonObject();
        event.add("keyframes", frames);
        objects.add("mod_event", event);
    }

    /**
     * 同一时刻的多条事件记录：第 2、3… 条依次加 {@code 1e-4} 秒（同一帧内触发，行为等价），
     * 保证新格式 keyframes 时间严格递增。
     */
    private List<Double> eventTimes(List<JsonObject> kfs, String scriptPath, String clipId) {
        java.util.Map<Double, Integer> counts = new java.util.LinkedHashMap<>();
        for (JsonObject kf : kfs) {
            counts.merge(time(kf), 1, Integer::sum);
        }
        for (java.util.Map.Entry<Double, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                report.manual(scriptPath + "/" + clipId + " 同一时刻 " + entry.getValue()
                        + " 条事件记录（t=" + entry.getKey() + "），已按 1e-4 秒错开");
            }
        }
        List<Double> times = new ArrayList<>(kfs.size());
        double last = Double.NEGATIVE_INFINITY;
        for (JsonObject kf : kfs) {
            double t = time(kf);
            if (t <= last) {
                t = last + 1e-4;
            }
            last = t;
            times.add(t);
        }
        return times;
    }

    // ------------------------------------------------------------------
    // 通道
    // ------------------------------------------------------------------

    private void mapChannel(TrackTypeId type, String channel, List<JsonObject> kfs,
                            JsonObject objects, String scriptPath, String clipId) {
        switch (channel) {
            case "dest", "source" -> mapRect(type, channel, kfs, objects);
            case "color" -> mapColor(type, kfs, objects, scriptPath, clipId);
            case "look_at_part" -> mapTriple(type, "look_at_part", channel, kfs, objects);
            case "look_at_offset", "facing_target_offset",
                 "yaw_base_from_offset", "yaw_base_to_offset" ->
                    mapOffset(type, channel, kfs, objects, scriptPath, clipId);
            case "facing_target_time" -> mapFreeze(type, "facing_target_freeze", channel, kfs, objects);
            case "look_at_time" -> mapFreeze(type, "look_at_freeze", channel, kfs, objects);
            case "yaw_base_from_time" -> mapFreeze(type, "yaw_base_from_freeze", channel, kfs, objects);
            case "yaw_base_to_time" -> mapFreeze(type, "yaw_base_to_freeze", channel, kfs, objects);
            case "facing_entity" -> report.manual(scriptPath + " " + clipId
                    + ": 旧 facing_entity 无对应新字段，未写入");
            default -> {
                String path = channel;
                if (channel.startsWith("selector_")) {
                    if (channel.endsWith("_follow")) {
                        path = channel.substring(0, channel.length() - "_follow".length()) + "_position_origin";
                    } else if (channel.endsWith("_facing_origin")) {
                        path = channel.substring(0, channel.length() - "_facing_origin".length())
                                + "_position_origin";
                    }
                }
                putChannel(type, path, channel, kfs, objects, scriptPath, clipId);
            }
        }
    }

    private void mapRect(TrackTypeId type, String channel, List<JsonObject> kfs, JsonObject objects) {
        for (String component : List.of("x", "y", "w", "h")) {
            List<JsonElement> values = new ArrayList<>(kfs.size());
            for (JsonObject kf : kfs) {
                JsonElement raw = kf.get(channel);
                JsonElement componentValue = null;
                if (raw != null && raw.isJsonObject()) {
                    componentValue = raw.getAsJsonObject().get(component);
                }
                values.add(componentValue);
            }
            writeSeries(type, channel + "." + component, kfs, values, objects);
        }
    }

    private void mapColor(TrackTypeId type, List<JsonObject> kfs, JsonObject objects,
                          String scriptPath, String clipId) {
        Set<String> unparsed = new LinkedHashSet<>();
        for (String component : List.of("r", "g", "b")) {
            int index = "r".equals(component) ? 0 : ("g".equals(component) ? 1 : 2);
            List<JsonElement> values = new ArrayList<>(kfs.size());
            for (JsonObject kf : kfs) {
                JsonElement raw = kf.get("color");
                JsonElement componentValue = null;
                if (raw != null && raw.isJsonObject()) {
                    componentValue = raw.getAsJsonObject().get(component);
                } else if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                    String hex = raw.getAsString();
                    Float[] rgb = parseHexColor(hex);
                    if (rgb != null) {
                        componentValue = new JsonPrimitive(rgb[index]);
                    } else {
                        unparsed.add(hex);
                    }
                }
                values.add(componentValue);
            }
            writeSeries(type, "color." + component, kfs, values, objects);
        }
        for (String hex : unparsed) {
            report.manual(scriptPath + " " + clipId + ": 关键帧 color '" + hex
                    + "' 无法解析（需要 #RRGGBB），该帧颜色分量按默认值处理");
        }
    }

    /** {@code #RRGGBB} → 三个 0~1 通道；非法返回 null。 */
    private static Float[] parseHexColor(String s) {
        if (s == null || s.length() != 7 || s.charAt(0) != '#') {
            return null;
        }
        try {
            int rgb = Integer.parseInt(s.substring(1), 16);
            return new Float[]{
                    ((rgb >> 16) & 0xFF) / 255f,
                    ((rgb >> 8) & 0xFF) / 255f,
                    (rgb & 0xFF) / 255f
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void mapTriple(TrackTypeId type, String pathPrefix, String channel,
                           List<JsonObject> kfs, JsonObject objects) {
        for (String component : List.of("x", "y", "z")) {
            List<JsonElement> values = new ArrayList<>(kfs.size());
            for (JsonObject kf : kfs) {
                JsonElement raw = kf.get(channel);
                JsonElement componentValue = null;
                if (raw != null && raw.isJsonObject()) {
                    componentValue = raw.getAsJsonObject().get(component);
                }
                values.add(componentValue);
            }
            writeSeries(type, pathPrefix + "." + component, kfs, values, objects);
        }
    }

    private void mapOffset(TrackTypeId type, String channel, List<JsonObject> kfs,
                           JsonObject objects, String scriptPath, String clipId) {
        boolean sawAxisVariant = false;
        List<JsonElement> spaces = new ArrayList<>(kfs.size());
        for (String component : List.of("x", "y", "z")) {
            List<JsonElement> values = new ArrayList<>(kfs.size());
            for (JsonObject kf : kfs) {
                JsonElement raw = kf.get(channel);
                JsonElement componentValue = null;
                if (raw != null && raw.isJsonObject()) {
                    JsonObject obj = raw.getAsJsonObject();
                    componentValue = obj.get(component);
                    if (componentValue == null && "x".equals(component)) {
                        componentValue = first(obj, "dx", "fwd");
                    } else if (componentValue == null && "y".equals(component)) {
                        componentValue = first(obj, "dy", "up");
                    } else if (componentValue == null && "z".equals(component)) {
                        componentValue = first(obj, "dz", "right");
                    }
                    if (componentValue != null && obj.has("dx")) {
                        sawAxisVariant = true;
                    }
                }
                values.add(componentValue);
            }
            writeSeries(type, channel + "." + component, kfs, values, objects);
        }
        for (JsonObject kf : kfs) {
            JsonElement raw = kf.get(channel);
            JsonElement space = null;
            if (raw != null && raw.isJsonObject()) {
                JsonObject obj = raw.getAsJsonObject();
                if (obj.has("space")) {
                    space = obj.get("space");
                } else if (obj.has("fwd") || obj.has("up") || obj.has("right")) {
                    space = new JsonPrimitive("base");
                }
            }
            spaces.add(space);
        }
        boolean any = false;
        for (JsonElement space : spaces) {
            if (space != null && !space.isJsonNull()) {
                any = true;
                break;
            }
        }
        if (any) {
            writeSeries(type, channel + ".space", kfs, spaces, objects);
        }
        if (sawAxisVariant) {
            report.semantic(scriptPath + " " + clipId + ": " + channel
                    + " 旧偏移键 dx/dy/dz → x/y/z（world 轴）");
        }
    }

    private void mapFreeze(TrackTypeId type, String newPath, String channel,
                           List<JsonObject> kfs, JsonObject objects) {
        boolean anySnapshot = false;
        for (JsonObject kf : kfs) {
            if ("snapshot".equals(str(kf, channel, "live"))) {
                anySnapshot = true;
                break;
            }
        }
        if (!anySnapshot) {
            return;
        }
        List<JsonElement> values = new ArrayList<>(kfs.size());
        for (JsonObject kf : kfs) {
            boolean snapshot = "snapshot".equals(str(kf, channel, "live"));
            values.add(new JsonPrimitive(snapshot));
        }
        writeSeries(type, newPath, kfs, values, objects);
    }

    private void putChannel(TrackTypeId type, String path, String channel, List<JsonObject> kfs,
                            JsonObject objects, String scriptPath, String clipId) {
        List<JsonElement> values = new ArrayList<>(kfs.size());
        for (JsonObject kf : kfs) {
            values.add(kf.get(channel));
        }
        if (!schema.exists(type, path)) {
            report.unknown(scriptPath + " | " + clipId + " | " + channel + " -> " + path
                    + " | " + values);
            return;
        }
        PropertyType valueType = schema.typeOf(type, path);
        Interp interp = schema.interpOf(type, path);
        if ((valueType == PropertyType.FLOAT || valueType == PropertyType.INT
                || valueType == PropertyType.NULLABLE_FLOAT) && interp != Interp.NONE) {
            checks.add(new Check(type, path, kfs, new ArrayList<>(values)));
        }
        writeSeries(type, path, kfs, values, objects);
    }

    private void writeSeries(TrackTypeId type, String path, List<JsonObject> kfs,
                             List<JsonElement> values, JsonObject objects) {
        putSeries(schema, report, type, path, kfs, values, objects);
    }

    // ------------------------------------------------------------------
    // 序列写入（供 PositionMapper 复用）
    // ------------------------------------------------------------------

    /**
     * 写一个对象路径：值序列按 {@code kfs} 的 time 对齐；Java {@code null} = 该帧缺失，
     * {@link JsonNull} = 显式 null。
     */
    public static void putSeries(Schema schema, Report report, TrackTypeId type, String path,
                                 List<JsonObject> kfs, List<JsonElement> rawValues, JsonObject objects) {
        if (!schema.exists(type, path)) {
            report.unknown("未知对象路径 '" + path + "'（类型 " + type.id() + "），未写入");
            return;
        }
        ObjectDef def = schema.def(type, path);
        PropertyMode mode = def.mode();
        PropertyType valueType = def.type();

        List<Double> times = new ArrayList<>();
        List<JsonElement> values = new ArrayList<>();
        for (int i = 0; i < kfs.size(); i++) {
            JsonElement raw = i < rawValues.size() ? rawValues.get(i) : null;
            JsonElement typed = typed(schema, type, path, raw);
            if (typed == null) {
                continue;
            }
            times.add(time(kfs.get(i)));
            values.add(typed);
        }
        if (values.isEmpty()) {
            return;
        }
        if (mode == PropertyMode.VALUE) {
            objects.add(path, value(values.get(0)));
            for (int i = 1; i < values.size(); i++) {
                if (!values.get(i).equals(values.get(0))) {
                    report.manual("value-only 对象 '" + path + "' 在旧关键帧里有多值，取首帧；需人工复核");
                    break;
                }
            }
            return;
        }

        // position.x/y/z 必须保留每个全局帧：脚本级 paths 的 from/to 端点要命中三轴关键帧时间。
        // color.r/g/b 同样保留每个全局帧（旧 hex 关键帧的离散色阶不被 LINEAR 共线压缩改写）。
        boolean preserveEveryFrame = (TrackTypeId.CAMERA.equals(type) && path.startsWith("position."))
                || path.startsWith("color.");

        if (!preserveEveryFrame) {
            boolean allEqual = true;
            for (int i = 1; i < values.size(); i++) {
                if (!values.get(i).equals(values.get(0))) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) {
                objects.add(path, value(values.get(0)));
                return;
            }

            if (valueType == PropertyType.FLOAT && schema.interpOf(type, path) == Interp.LINEAR) {
                compressLinear(times, values);
            }
        }
        if (values.size() == 1) {
            objects.add(path, value(values.get(0)));
            return;
        }
        JsonArray frames = new JsonArray();
        for (int i = 0; i < values.size(); i++) {
            JsonObject frame = new JsonObject();
            frame.addProperty("time", times.get(i));
            frame.add("value", values.get(i));
            frames.add(frame);
        }
        JsonObject object = new JsonObject();
        object.add("keyframes", frames);
        objects.add(path, object);
    }

    private static void compressLinear(List<Double> times, List<JsonElement> values) {
        boolean changed = true;
        while (changed && values.size() > 2) {
            changed = false;
            for (int i = 1; i < values.size() - 1; i++) {
                double t0 = times.get(i - 1);
                double t1 = times.get(i);
                double t2 = times.get(i + 1);
                if (t2 == t0) {
                    continue;
                }
                double v0 = values.get(i - 1).getAsDouble();
                double v1 = values.get(i).getAsDouble();
                double v2 = values.get(i + 1).getAsDouble();
                double s = (t1 - t0) / (t2 - t0);
                double lerp = v0 + (v2 - v0) * s;
                if (Math.abs(v1 - lerp) <= 1e-9 * Math.max(1.0, Math.abs(v1))) {
                    times.remove(i);
                    values.remove(i);
                    changed = true;
                    break;
                }
            }
        }
    }

    /** 按 schema 类型转换旧值；{@code null} = 缺失（NULLABLE_FLOAT 缺失直接跳过）。 */
    public static JsonElement typed(Schema schema, TrackTypeId type, String path, JsonElement raw) {
        PropertyType t = schema.typeOf(type, path);
        if (raw == null) {
            if (t == PropertyType.NULLABLE_FLOAT) {
                return null;
            }
            Object def = schema.zeroOf(type, path);
            return typedValue(t, def);
        }
        if (raw.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (t == null) {
            return deep(raw);
        }
        try {
            return switch (t) {
                case FLOAT -> new JsonPrimitive((float) raw.getAsDouble());
                case INT -> new JsonPrimitive(raw.getAsInt());
                case BOOL -> new JsonPrimitive(raw.getAsBoolean());
                case STRING, ENUM, REF -> new JsonPrimitive(raw.getAsString());
                case NULLABLE_FLOAT -> new JsonPrimitive((float) raw.getAsDouble());
                case STRUCT, EVENT_RECORD -> deep(raw);
            };
        } catch (RuntimeException e) {
            Object def = schema.zeroOf(type, path);
            return typedValue(t, def);
        }
    }

    private static JsonElement typedValue(PropertyType t, Object def) {
        if (def == null) {
            return t == PropertyType.NULLABLE_FLOAT ? null : JsonNull.INSTANCE;
        }
        if (def instanceof Float f) {
            return new JsonPrimitive(f);
        }
        if (def instanceof Double d) {
            return new JsonPrimitive(d.floatValue());
        }
        if (def instanceof Number n) {
            if (t == PropertyType.FLOAT || t == PropertyType.NULLABLE_FLOAT) {
                return new JsonPrimitive(n.floatValue());
            }
            return new JsonPrimitive(n.intValue());
        }
        if (def instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (def instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (def instanceof JsonElement e) {
            return deep(e);
        }
        return new JsonPrimitive(String.valueOf(def));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    public static Set<String> channels(List<JsonObject> kfs) {
        Set<String> set = new LinkedHashSet<>();
        for (JsonObject kf : kfs) {
            for (String key : kf.keySet()) {
                if (!"time".equals(key)) {
                    set.add(key);
                }
            }
        }
        return set;
    }

    public static double time(JsonObject kf) {
        JsonElement t = kf.get("time");
        return t == null || t.isJsonNull() ? 0d : t.getAsDouble();
    }

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return def;
        }
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static JsonObject value(JsonElement v) {
        JsonObject o = new JsonObject();
        o.add("value", v == null ? JsonNull.INSTANCE : v);
        return o;
    }

    public static JsonElement deep(JsonElement e) {
        return e == null ? JsonNull.INSTANCE : e.deepCopy();
    }

    private static JsonElement first(JsonObject obj, String a, String b) {
        JsonElement e = obj.get(a);
        if (e != null) {
            return e;
        }
        return obj.get(b);
    }
}
