package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本解析器 — 将 JSON 字符串解析为 CinematicScript POJO
 * <p>
 * 使用 Gson 的 JsonElement 树 API 手动解析，而非反射绑定。
 * 通过 {@link SchemaLoader} 驱动字段解析，不再为每种轨道类型编写独立解析方法。
 */
public class ScriptParser {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptParser");

    /**
     * 解析异常 — 包含字段路径信息
     */
    public static class ScriptParseException extends Exception {
        private final String fieldPath;

        public ScriptParseException(String fieldPath, String message) {
            super(fieldPath + ": " + message);
            this.fieldPath = fieldPath;
        }

        public ScriptParseException(String fieldPath, String message, Throwable cause) {
            super(fieldPath + ": " + message, cause);
            this.fieldPath = fieldPath;
        }

        public String getFieldPath() {
            return fieldPath;
        }
    }

    private ScriptParser() {}

    // ========== 入口方法 ==========

    public static CinematicScript parse(String json) throws ScriptParseException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            throw new ScriptParseException("<root>", "JSON 语法错误: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new ScriptParseException("<root>", "根元素必须是 JSON 对象");
        }
        JsonObject rootObj = root.getAsJsonObject();
        ScriptMeta meta = parseMeta(rootObj);
        Timeline timeline = parseTimeline(rootObj, "timeline");
        return new CinematicScript(meta, timeline);
    }

    // ========== ScriptMeta 解析 ==========

    private static ScriptMeta parseMeta(JsonObject root) throws ScriptParseException {
        String p = "meta";
        JsonObject metaObj = requireObject(root, p, "meta");

        String id = requireString(metaObj, p, "id");
        String name = requireString(metaObj, p, "name");
        String author = requireString(metaObj, p, "author");
        int version = requireInt(metaObj, p, "version");
        String description = optString(metaObj, "description", "");

        if (!id.matches("^[a-zA-Z0-9_]{1,32}$")) {
            throw new ScriptParseException(p + ".id", "必须匹配 ^[a-zA-Z0-9_]{1,32}$，实际: " + id);
        }
        if (name.length() > 50) {
            throw new ScriptParseException(p + ".name", "最长50字符，实际: " + name.length());
        }
        if (author.length() > 30) {
            throw new ScriptParseException(p + ".author", "最长30字符，实际: " + author.length());
        }
        if (version != 3) {
            throw new ScriptParseException(p + ".version", "当前仅支持版本3，实际: " + version);
        }

        boolean blockKeyboard = optBool(metaObj, "block_keyboard", true);
        boolean blockMouse = optBool(metaObj, "block_mouse", true);
        boolean blockMobAi = optBool(metaObj, "block_mob_ai", false);
        boolean hideHud = optBool(metaObj, "hide_hud", true);
        Boolean hideArm = metaObj.has("hide_arm") ? metaObj.get("hide_arm").getAsBoolean() : null;
        Boolean suppressBob = metaObj.has("suppress_bob") ? metaObj.get("suppress_bob").getAsBoolean() : null;
        Boolean hideChat = metaObj.has("hide_chat") ? metaObj.get("hide_chat").getAsBoolean() : null;
        Boolean hideScoreboard = metaObj.has("hide_scoreboard") ? metaObj.get("hide_scoreboard").getAsBoolean() : null;
        Boolean hideActionBar = metaObj.has("hide_action_bar") ? metaObj.get("hide_action_bar").getAsBoolean() : null;
        Boolean hideTitle = metaObj.has("hide_title") ? metaObj.get("hide_title").getAsBoolean() : null;
        Boolean hideSubtitles = metaObj.has("hide_subtitles") ? metaObj.get("hide_subtitles").getAsBoolean() : null;
        Boolean hideHotbar = metaObj.has("hide_hotbar") ? metaObj.get("hide_hotbar").getAsBoolean() : null;
        Boolean hideCrosshair = metaObj.has("hide_crosshair") ? metaObj.get("hide_crosshair").getAsBoolean() : null;
        Boolean hideBossbar = metaObj.has("hide_bossbar") ? metaObj.get("hide_bossbar").getAsBoolean() : null;
        Boolean hideSkipHud = metaObj.has("hide_skip_hud") ? metaObj.get("hide_skip_hud").getAsBoolean() : null;
        boolean renderPlayerModel = optBool(metaObj, "render_player_model", false);
        boolean pauseWhenGamePaused = optBool(metaObj, "pause_when_game_paused", true);
        boolean interruptible = optBool(metaObj, "interruptible", true);
        boolean skippable = optBool(metaObj, "skippable", true);
        boolean holdAtEnd = optBool(metaObj, "hold_at_end", true);

        ScriptMeta.RuntimeBehavior behavior = new ScriptMeta.RuntimeBehavior(
                blockKeyboard, blockMouse, blockMobAi,
                hideHud, hideArm, suppressBob,
                hideChat, hideScoreboard, hideActionBar,
                hideTitle, hideSubtitles, hideHotbar, hideCrosshair,
                hideBossbar, hideSkipHud,
                renderPlayerModel,
                pauseWhenGamePaused, interruptible, skippable,
                holdAtEnd);

        // 脚本维度限制（可选）
        String dimension = optString(metaObj, "dimension", "");

        // 触发器定义（可选）
        List<TriggerDefinition> triggers = new ArrayList<>();
        if (metaObj.has("triggers") && metaObj.get("triggers").isJsonArray()) {
            JsonArray trigArr = metaObj.getAsJsonArray("triggers");
            for (int i = 0; i < trigArr.size(); i++) {
                triggers.add(parseTriggerDefinition(trigArr.get(i).getAsJsonObject(), p + ".triggers[" + i + "]"));
            }
        }

        return new ScriptMeta(id, name, author, version, description, behavior, dimension, triggers);
    }

    // ========== Timeline 解析 ==========

    private static Timeline parseTimeline(JsonObject root, String key) throws ScriptParseException {
        String p = key;
        if (!root.has(key)) {
            throw new ScriptParseException(p, "缺少必填字段: " + key);
        }
        JsonObject timelineObj = requireObject(root, p, key);
        float totalDuration = requireFloat(timelineObj, p, "total_duration");
        if (totalDuration == 0f) {
            throw new ScriptParseException(p + ".total_duration", "不允许为0，正数=有限时长，负数=无限时长，实际: " + totalDuration);
        }

        JsonArray tracksArr = requireArray(timelineObj, p, "tracks");
        List<TimelineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < tracksArr.size(); i++) {
            tracks.add(parseTrack(tracksArr.get(i).getAsJsonObject(), p + ".tracks[" + i + "]"));
        }

        validateTracks(tracks, p);
        return new Timeline(totalDuration, tracks);
    }

    // ========== Track 解析（统一 schema 驱动）==========

    private static TimelineTrack parseTrack(JsonObject trackObj, String p) throws ScriptParseException {
        String typeStr = requireString(trackObj, p, "type");
        TrackType type = parseTrackType(typeStr, p + ".type");
        JsonArray clipsArr = requireArray(trackObj, p, "clips");

        List<Clip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            clips.add(parseClip(clipsArr.get(i).getAsJsonObject(), p + ".clips[" + i + "]", type));
        }

        return new TimelineTrack(type, clips);
    }

    // ========== Clip 解析（统一 schema 驱动）==========

    private static Clip parseClip(JsonObject obj, String p, TrackType type) throws ScriptParseException {
        float startTime = requireFloat(obj, p, "start_time");
        float duration = requireFloat(obj, p, "duration");

        // 解析类型特有字段到 data map
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String fieldName = entry.getKey();
            if ("start_time".equals(fieldName) || "duration".equals(fieldName) || "keyframes".equals(fieldName)) {
                continue; // 通用字段跳过
            }
            Object value = parseFieldBySchema(fieldName, entry.getValue(), p, type, false);
            if (value != null) {
                data.put(fieldName, value);
            }
        }

        // 校验必填字段（来自 schema）
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getClipFields(type).entrySet()) {
            if (e.getValue().required() && !data.containsKey(e.getKey()) && !obj.has(e.getKey())) {
                throw new ScriptParseException(p + "." + e.getKey(), "缺少必填字段");
            }
        }

        // 解析关键帧
        List<Keyframe> keyframes = new ArrayList<>();
        if (obj.has("keyframes")) {
            JsonArray kfArr = obj.getAsJsonArray("keyframes");
            for (int i = 0; i < kfArr.size(); i++) {
                keyframes.add(parseKeyframe(kfArr.get(i).getAsJsonObject(), p + ".keyframes[" + i + "]", type));
            }
        }

        // Letterbox 简写兼容：缺 keyframes 时从 clip 级 aspect_ratio 自动生成两个关键帧
        if (type == TrackType.LETTERBOX && !obj.has("keyframes")) {
            float ratio = optFloat(obj, "aspect_ratio", 2.35f);
            Keyframe k0 = new Keyframe(0f, type, Map.of("aspect_ratio", ratio));
            Keyframe k1 = new Keyframe(duration, type, Map.of("aspect_ratio", ratio));
            keyframes = List.of(k0, k1);
        }

        // 验证
        if (type == TrackType.CAMERA && keyframes.isEmpty()) {
            throw new ScriptParseException(p, "camera clip 的 keyframes 至少1个");
        }
        if (duration == 0f) {
            throw new ScriptParseException(p + ".duration", "不允许为0，正数=有限时长，负数=无限时长，实际: " + duration);
        }
        if (data.containsKey("curve")) {
            BezierCurve curve = (BezierCurve) data.get("curve");
            if (curve != null && !curve.isValid()) {
                throw new ScriptParseException(p + ".curve", "control_points 必须恰好2个点");
            }
        }

        // 验证关键帧时间单调递增
        for (int i = 1; i < keyframes.size(); i++) {
            if (keyframes.get(i).getTime() <= keyframes.get(i - 1).getTime()) {
                throw new ScriptParseException(p + ".keyframes[" + i + "].time",
                        "关键帧时间必须单调递增，前一帧: " + keyframes.get(i - 1).getTime());
            }
        }

        return new Clip(startTime, duration, type, data, keyframes);
    }
    // ========== Keyframe 解析（统一 schema 驱动）==========

    private static Keyframe parseKeyframe(JsonObject obj, String p, TrackType type) throws ScriptParseException {
        float time = requireFloat(obj, p, "time");
        if (time < 0) {
            throw new ScriptParseException(p + ".time", "不能为负数: " + time);
        }
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String fieldName = entry.getKey();
            if ("time".equals(fieldName)) continue;
            Object value = parseFieldBySchema(fieldName, entry.getValue(), p, type, true);
            if (value != null) data.put(fieldName, value);
        }
        // 校验必填字段（来自 schema）
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getKeyframeFields(type).entrySet()) {
            if (e.getValue().required() && !data.containsKey(e.getKey()) && !obj.has(e.getKey())) {
                throw new ScriptParseException(p + "." + e.getKey(), "缺少必填字段");
            }
        }
        return new Keyframe(time, type, data);
    }

    /**
     * 根据 schema 字段类型解析一个 JSON 值
     */
    private static Object parseFieldBySchema(String fieldName, JsonElement value, String p,
                                              TrackType type, boolean isKeyframe) throws ScriptParseException {
        SchemaLoader.FieldDef def = isKeyframe
                ? SchemaLoader.getKeyframeFields(type).get(fieldName)
                : SchemaLoader.getClipFields(type).get(fieldName);

        if (def == null) {
            // 不在 schema 中的字段 — 简单类型自动解析（向前兼容）
            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isNumber()) {
                    return value.getAsFloat();
                } else if (value.getAsJsonPrimitive().isBoolean()) {
                    return value.getAsBoolean();
                } else {
                    return value.getAsString();
                }
            } else if (value.isJsonArray()) {
                return parseUnknownArray(value.getAsJsonArray(), p + "." + fieldName);
            } else if (value.isJsonObject()) {
                return parseUnknownObject(value.getAsJsonObject(), p + "." + fieldName);
            }
            return null;
        }

        return switch (def.type()) {
            case "float", "int" -> value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : null;
            case "string" -> value.getAsString();
            case "bool" -> value.getAsBoolean();
            case "position" -> {
                if (!value.isJsonObject()) throw new ScriptParseException(p + "." + fieldName, "position 需要 JSON 对象");
                // 从 clip 级无法直接获取 position_mode，但我们会从实际 JSON 推断
                JsonObject posObj = value.getAsJsonObject();
                boolean relative = posObj.has("dx");
                if (relative) {
                    yield PositionData.relative(
                            requireFloat(posObj, p + "." + fieldName, "dx"),
                            requireFloat(posObj, p + "." + fieldName, "dy"),
                            requireFloat(posObj, p + "." + fieldName, "dz"));
                } else {
                    yield PositionData.absolute(
                            requireFloat(posObj, p + "." + fieldName, "x"),
                            requireFloat(posObj, p + "." + fieldName, "y"),
                            requireFloat(posObj, p + "." + fieldName, "z"));
                }
            }
            case "bezier_curve" -> parseBezierCurve(value.getAsJsonObject(), p + "." + fieldName);
            case "map" -> {
                if (value.isJsonObject()) {
                    yield parseDataMap(value.getAsJsonObject(), p + "." + fieldName);
                }
                yield null;
            }
            default -> null;
        };
    }

    // ========== BezierCurve 解析 ==========

    private static BezierCurve parseBezierCurve(JsonObject obj, String p) throws ScriptParseException {
        String type = optString(obj, "type", "bezier");
        JsonArray cpArr = requireArray(obj, p, "control_points");
        if (cpArr.size() != 2) {
            throw new ScriptParseException(p + ".control_points", "必须恰好2个控制点，实际: " + cpArr.size());
        }
        List<Vec3> controlPoints = new ArrayList<>();
        for (int i = 0; i < cpArr.size(); i++) {
            controlPoints.add(parseVec3(cpArr.get(i).getAsJsonObject(), p + ".control_points[" + i + "]"));
        }
        return new BezierCurve(type, controlPoints);
    }

    // ========== PositionData 解析 ==========

    private static PositionData parsePositionData(JsonObject obj, String p, boolean positionModeRelative) throws ScriptParseException {
        if (positionModeRelative) {
            if (!obj.has("dx")) {
                throw new ScriptParseException(p, "relative 模式需要 dx/dy/dz 字段");
            }
            float dx = requireFloat(obj, p, "dx");
            float dy = requireFloat(obj, p, "dy");
            float dz = requireFloat(obj, p, "dz");
            return PositionData.relative(dx, dy, dz);
        } else {
            if (!obj.has("x")) {
                throw new ScriptParseException(p, "absolute 模式需要 x/y/z 字段");
            }
            float x = requireFloat(obj, p, "x");
            float y = requireFloat(obj, p, "y");
            float z = requireFloat(obj, p, "z");
            return PositionData.absolute(x, y, z);
        }
    }

    // ========== 触发器定义解析 ==========

    private static TriggerDefinition parseTriggerDefinition(JsonObject obj, String p) throws ScriptParseException {
        String type = requireString(obj, p, "type");
        Map<String, Object> conditions = obj.has("conditions")
                ? parseDataMap(obj.getAsJsonObject("conditions"), p + ".conditions")
                : new HashMap<>();
        boolean repeatable = optBool(obj, "repeatable", false);
        float delay = optFloat(obj, "delay", 0f);
        boolean onEnter = optBool(obj, "on_enter", false);
        float exitBuffer = optFloat(obj, "exit_buffer", 0f);
        return new TriggerDefinition(type, conditions, repeatable, delay, onEnter, exitBuffer);
    }

    // ========== 验证方法 ==========

    private static void validateTracks(List<TimelineTrack> tracks, String p) throws ScriptParseException {
        long cameraCount = tracks.stream().filter(t -> t.getType() == TrackType.CAMERA).count();
        if (cameraCount > 1) {
            LOGGER.warn("检测到 {} 条 CAMERA 轨道，当前仅支持第1条", cameraCount);
        }
        long letterboxCount = tracks.stream().filter(t -> t.getType() == TrackType.LETTERBOX).count();
        if (letterboxCount > 1) {
            LOGGER.warn("检测到 {} 条 LETTERBOX 轨道，建议最多1条", letterboxCount);
        }
        long eventCount = tracks.stream().filter(t -> t.getType() == TrackType.EVENT).count();
        if (eventCount > 1) {
            LOGGER.warn("检测到 {} 条 EVENT 轨道，建议最多1条", eventCount);
        }

        for (TimelineTrack track : tracks) {
            if (track.getType() == TrackType.CAMERA) {
                List<Clip> clips = track.getClips();
                for (int i = 1; i < clips.size(); i++) {
                    Clip clip = clips.get(i);
                    Clip prevClip = clips.get(i - 1);
                    if (clip.isMorph() && prevClip != null) {
                        if (prevClip.isPositionModeRelative() != clip.isPositionModeRelative()) {
                            LOGGER.warn("morph 相邻 clip 的 position_mode 不同（{} → {}），运行时已统一为世界坐标，混合结果可能非预期",
                                    prevClip.isPositionModeRelative() ? "relative" : "absolute",
                                    clip.isPositionModeRelative() ? "relative" : "absolute");
                        }
                    }
                }
            }
        }
    }

    // ========== 枚举解析 ==========

    private static TrackType parseTrackType(String value, String p) throws ScriptParseException {
        try {
            return TrackType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ScriptParseException(p, "未知的轨道类型: " + value + "，支持: camera/letterbox/audio/event/mod_event");
        }
    }

    // ========== JSON 辅助方法 ==========

    private static Vec3 parseVec3(JsonObject obj, String p) throws ScriptParseException {
        return new Vec3(
                requireFloat(obj, p, "x"),
                requireFloat(obj, p, "y"),
                requireFloat(obj, p, "z")
        );
    }

    private static Map<String, Object> parseDataMap(JsonObject obj, String p) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement val = entry.getValue();
            if (val.isJsonPrimitive()) {
                if (val.getAsJsonPrimitive().isNumber()) {
                    map.put(entry.getKey(), val.getAsDouble());
                } else if (val.getAsJsonPrimitive().isBoolean()) {
                    map.put(entry.getKey(), val.getAsBoolean());
                } else {
                    map.put(entry.getKey(), val.getAsString());
                }
            } else if (val.isJsonObject()) {
                map.put(entry.getKey(), parseDataMap(val.getAsJsonObject(), p + "." + entry.getKey()));
            } else if (val.isJsonArray()) {
                map.put(entry.getKey(), parseDataArray(val.getAsJsonArray(), p + "." + entry.getKey()));
            }
        }
        return map;
    }

    private static Object parseDataArray(JsonArray arr, String p) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement val = arr.get(i);
            if (val.isJsonPrimitive()) {
                if (val.getAsJsonPrimitive().isNumber()) {
                    list.add(val.getAsDouble());
                } else if (val.getAsJsonPrimitive().isBoolean()) {
                    list.add(val.getAsBoolean());
                } else {
                    list.add(val.getAsString());
                }
            } else if (val.isJsonObject()) {
                list.add(parseDataMap(val.getAsJsonObject(), p + "[" + i + "]"));
            } else if (val.isJsonArray()) {
                list.add(parseDataArray(val.getAsJsonArray(), p + "[" + i + "]"));
            }
        }
        return list;
    }

    // Fallback: for unknown JSON objects, parse as Map
    private static Map<String, Object> parseUnknownObject(JsonObject obj, String p) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement val = entry.getValue();
            if (val.isJsonPrimitive()) {
                if (val.getAsJsonPrimitive().isNumber()) {
                    map.put(entry.getKey(), val.getAsFloat());
                } else if (val.getAsJsonPrimitive().isBoolean()) {
                    map.put(entry.getKey(), val.getAsBoolean());
                } else {
                    map.put(entry.getKey(), val.getAsString());
                }
            } else if (val.isJsonObject()) {
                map.put(entry.getKey(), parseUnknownObject(val.getAsJsonObject(), p + "." + entry.getKey()));
            } else if (val.isJsonArray()) {
                map.put(entry.getKey(), parseUnknownArray(val.getAsJsonArray(), p + "." + entry.getKey()));
            }
        }
        return map;
    }

    private static Object parseUnknownArray(JsonArray arr, String p) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement val = arr.get(i);
            if (val.isJsonPrimitive()) {
                if (val.getAsJsonPrimitive().isNumber()) {
                    list.add(val.getAsFloat());
                } else if (val.getAsJsonPrimitive().isBoolean()) {
                    list.add(val.getAsBoolean());
                } else {
                    list.add(val.getAsString());
                }
            } else if (val.isJsonObject()) {
                list.add(parseUnknownObject(val.getAsJsonObject(), p + "[" + i + "]"));
            } else if (val.isJsonArray()) {
                list.add(parseUnknownArray(val.getAsJsonArray(), p + "[" + i + "]"));
            }
        }
        return list;
    }

    // ========== JSON 读取辅助（必填/可选） ==========

    private static String requireString(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key)) throw new ScriptParseException(p + "." + key, "缺少必填字段");
        return obj.get(key).getAsString();
    }

    private static int requireInt(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key)) throw new ScriptParseException(p + "." + key, "缺少必填字段");
        try {
            return obj.get(key).getAsInt();
        } catch (NumberFormatException e) {
            throw new ScriptParseException(p + "." + key, "期望整数，实际: " + obj.get(key));
        }
    }

    private static float requireFloat(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key)) throw new ScriptParseException(p + "." + key, "缺少必填字段");
        try {
            return obj.get(key).getAsFloat();
        } catch (NumberFormatException e) {
            throw new ScriptParseException(p + "." + key, "期望浮点数，实际: " + obj.get(key));
        }
    }

    private static JsonObject requireObject(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new ScriptParseException(p + "." + key, "缺少必填对象字段");
        }
        return obj.getAsJsonObject(key);
    }

    private static JsonArray requireArray(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new ScriptParseException(p + "." + key, "缺少必填数组字段");
        }
        return obj.getAsJsonArray(key);
    }

    private static String optString(JsonObject obj, String key, String defaultVal) {
        return obj.has(key) ? obj.get(key).getAsString() : defaultVal;
    }

    private static float optFloat(JsonObject obj, String key, float defaultVal) {
        return obj.has(key) ? obj.get(key).getAsFloat() : defaultVal;
    }

    private static int optInt(JsonObject obj, String key, int defaultVal) {
        return obj.has(key) ? obj.get(key).getAsInt() : defaultVal;
    }

    private static boolean optBool(JsonObject obj, String key, boolean defaultVal) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : defaultVal;
    }
}
