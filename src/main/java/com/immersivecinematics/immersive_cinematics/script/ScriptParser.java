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
 * 优势：
 * <ul>
 *   <li>精确控制多态 clips 数组的反序列化</li>
 *   <li>PositionData 的 dx/dy/dz vs x/y/z 双格式处理</li>
 *   <li>每一步都可插入验证逻辑，提供清晰的错误路径</li>
 *   <li>POJO 类保持纯净，不耦合 Gson 注解</li>
 * </ul>
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

    private ScriptParser() {} // 禁止实例化，只提供静态方法

    // ========== 入口方法 ==========

    /**
     * 将 JSON 字符串解析为 CinematicScript
     *
     * @param json 脚本 JSON 字符串
     * @return 解析后的 CinematicScript 对象
     * @throws ScriptParseException 解析或验证失败
     */
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

        // 提取 meta 子对象
        JsonObject metaObj = requireObject(root, p, "meta");

        // 元信息
        String id = requireString(metaObj, p, "id");
        String name = requireString(metaObj, p, "name");
        String author = requireString(metaObj, p, "author");
        int version = requireInt(metaObj, p, "version");
        String description = optString(metaObj, "description", "");

        // 验证元信息
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

        // 运行时行为（14个布尔）
        boolean blockKeyboard = optBool(metaObj, "block_keyboard", true);
        boolean blockMouse = optBool(metaObj, "block_mouse", true);
        boolean blockMobAi = optBool(metaObj, "block_mob_ai", false);
        boolean hideHud = optBool(metaObj, "hide_hud", true);
        boolean hideArm = optBool(metaObj, "hide_arm", true);
        boolean suppressBob = optBool(metaObj, "suppress_bob", true);
        boolean blockChat = optBool(metaObj, "block_chat", false);
        boolean blockScoreboard = optBool(metaObj, "block_scoreboard", false);
        boolean blockActionBar = optBool(metaObj, "block_action_bar", false);
        boolean blockParticles = optBool(metaObj, "block_particles", false);
        boolean renderPlayerModel = optBool(metaObj, "render_player_model", true);
        boolean pauseWhenGamePaused = optBool(metaObj, "pause_when_game_paused", true);
        boolean interruptible = optBool(metaObj, "interruptible", true);
        boolean holdAtEnd = optBool(metaObj, "hold_at_end", false);

        // 脚本级插值（可选，null=LINEAR，不施加全局曲线）
        InterpolationType scriptInterpolation = null;
        if (metaObj.has("interpolation")) {
            scriptInterpolation = parseInterpolationType(
                    metaObj.get("interpolation").getAsString(), p + ".interpolation");
        }

        // 曲线组合模式（可选，null=OVERRIDE）
        CurveCompositionMode compositionMode = null;
        if (metaObj.has("curve_composition_mode")) {
            compositionMode = parseCurveCompositionMode(
                    metaObj.get("curve_composition_mode").getAsString(), p + ".curve_composition_mode");
        }

        return new ScriptMeta(id, name, author, version, description,
                new ScriptMeta.RuntimeBehavior(blockKeyboard, blockMouse, blockMobAi,
                        hideHud, hideArm, suppressBob,
                        blockChat, blockScoreboard, blockActionBar,
                        blockParticles, renderPlayerModel,
                        pauseWhenGamePaused, interruptible, holdAtEnd),
                scriptInterpolation, compositionMode);
    }

    // ========== Timeline 解析 ==========

    private static Timeline parseTimeline(JsonObject root, String key) throws ScriptParseException {
        String p = key;
        if (!root.has(key)) {
            throw new ScriptParseException(p, "缺少必填字段: " + key);
        }

        JsonObject timelineObj = requireObject(root, p, key);
        float totalDuration = requireFloat(timelineObj, p, "total_duration");

        // 验证 total_duration
        if (totalDuration != -1f && totalDuration <= 0f) {
            throw new ScriptParseException(p + ".total_duration", "只允许正数或 -1，实际: " + totalDuration);
        }

        JsonArray tracksArr = requireArray(timelineObj, p, "tracks");
        List<TimelineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < tracksArr.size(); i++) {
            tracks.add(parseTrack(tracksArr.get(i).getAsJsonObject(), p + ".tracks[" + i + "]"));
        }

        // 轨道级验证
        validateTracks(tracks, p);

        return new Timeline(totalDuration, tracks);
    }

    // ========== Track 解析 ==========

    private static TimelineTrack parseTrack(JsonObject trackObj, String p) throws ScriptParseException {
        String typeStr = requireString(trackObj, p, "type");
        TrackType type = parseTrackType(typeStr, p + ".type");
        JsonArray clipsArr = requireArray(trackObj, p, "clips");

        List<?> clips = switch (type) {
            case CAMERA -> parseCameraClips(clipsArr, p + ".clips");
            case LETTERBOX -> parseLetterboxClips(clipsArr, p + ".clips");
            case AUDIO -> parseAudioClips(clipsArr, p + ".clips");
            case EVENT -> parseEventClips(clipsArr, p + ".clips");
            case MOD_EVENT -> parseModEventClips(clipsArr, p + ".clips");
        };

        return new TimelineTrack(type, clips);
    }

    // ========== CameraClip 解析 ==========

    private static List<CameraClip> parseCameraClips(JsonArray clipsArr, String p) throws ScriptParseException {
        List<CameraClip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            clips.add(parseCameraClip(clipsArr.get(i).getAsJsonObject(), p + "[" + i + "]"));
        }
        return clips;
    }

    private static CameraClip parseCameraClip(JsonObject obj, String p) throws ScriptParseException {
        float startTime = requireFloat(obj, p, "start_time");
        float duration = requireFloat(obj, p, "duration");
        String transition = optString(obj, "transition", "cut");
        float crossfadeDuration = optFloat(obj, "crossfade_duration", 0.5f);
        InterpolationType interpolation = parseInterpolationType(
                optString(obj, "interpolation", "smooth"), p + ".interpolation");
        InterpolationScope interpolationScope = parseInterpolationScope(
                optString(obj, "interpolation_scope", "segment"), p + ".interpolation_scope");
        BezierCurve curve = obj.has("curve") ? parseBezierCurve(obj.getAsJsonObject("curve"), p + ".curve") : null;
        boolean positionModeRelative = "relative".equals(optString(obj, "position_mode", "relative"));
        boolean loop = optBool(obj, "loop", false);
        int loopCount = optInt(obj, "loop_count", -1);

        // 解析关键帧
        JsonArray kfArr = requireArray(obj, p, "keyframes");
        List<CameraKeyframe> keyframes = new ArrayList<>();
        for (int i = 0; i < kfArr.size(); i++) {
            keyframes.add(parseCameraKeyframe(kfArr.get(i).getAsJsonObject(), p + ".keyframes[" + i + "]", positionModeRelative));
        }

        // 验证
        if (keyframes.isEmpty()) {
            throw new ScriptParseException(p, "camera clip 的 keyframes 至少1个");
        }
        if (duration != -1f && duration <= 0f) {
            throw new ScriptParseException(p + ".duration", "只允许正数或 -1，实际: " + duration);
        }
        if (curve != null && !curve.isValid()) {
            throw new ScriptParseException(p + ".curve", "control_points 必须恰好2个点");
        }

        // 验证关键帧时间单调递增
        for (int i = 1; i < keyframes.size(); i++) {
            if (keyframes.get(i).getTime() <= keyframes.get(i - 1).getTime()) {
                throw new ScriptParseException(p + ".keyframes[" + i + "].time",
                        "关键帧时间必须单调递增，前一帧: " + keyframes.get(i - 1).getTime());
            }
        }

        return new CameraClip(startTime, duration, transition, crossfadeDuration,
                interpolation, interpolationScope, curve, positionModeRelative, loop, loopCount, keyframes);
    }

    // ========== CameraKeyframe 解析 ==========

    private static CameraKeyframe parseCameraKeyframe(JsonObject obj, String p, boolean positionModeRelative) throws ScriptParseException {
        float time = requireFloat(obj, p, "time");
        PositionData position = parsePositionData(obj.getAsJsonObject("position"), p + ".position", positionModeRelative);
        float yaw = requireFloat(obj, p, "yaw");
        float pitch = requireFloat(obj, p, "pitch");
        float roll = requireFloat(obj, p, "roll");
        float fov = requireFloat(obj, p, "fov");
        float zoom = optFloat(obj, "zoom", 1.0f);
        float dof = optFloat(obj, "dof", 0f);

        // 逐关键帧插值覆盖（可选，null=使用 clip 默认）
        InterpolationType keyframeInterpolation = null;
        if (obj.has("interpolation")) {
            keyframeInterpolation = parseInterpolationType(
                    obj.get("interpolation").getAsString(), p + ".interpolation");
        }

        if (time < 0) {
            throw new ScriptParseException(p + ".time", "不能为负数: " + time);
        }

        return new CameraKeyframe(time, position, yaw, pitch, roll, fov, zoom, dof, keyframeInterpolation);
    }

    // ========== PositionData 解析 ==========

    private static PositionData parsePositionData(JsonObject obj, String p, boolean positionModeRelative) throws ScriptParseException {
        if (obj == null) {
            throw new ScriptParseException(p, "缺少必填字段: position");
        }

        if (positionModeRelative) {
            // relative 模式：dx/dy/dz
            if (!obj.has("dx")) {
                throw new ScriptParseException(p, "relative 模式需要 dx/dy/dz 字段");
            }
            float dx = requireFloat(obj, p, "dx");
            float dy = requireFloat(obj, p, "dy");
            float dz = requireFloat(obj, p, "dz");
            return PositionData.relative(dx, dy, dz);
        } else {
            // absolute 模式：x/y/z
            if (!obj.has("x")) {
                throw new ScriptParseException(p, "absolute 模式需要 x/y/z 字段");
            }
            float x = requireFloat(obj, p, "x");
            float y = requireFloat(obj, p, "y");
            float z = requireFloat(obj, p, "z");
            return PositionData.absolute(x, y, z);
        }
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

    // ========== LetterboxClip 解析 ==========

    private static List<LetterboxClip> parseLetterboxClips(JsonArray clipsArr, String p) throws ScriptParseException {
        List<LetterboxClip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            JsonObject obj = clipsArr.get(i).getAsJsonObject();
            String cp = p + "[" + i + "]";
            clips.add(new LetterboxClip(
                    requireFloat(obj, cp, "start_time"),
                    requireFloat(obj, cp, "duration"),
                    optBool(obj, "enabled", true),
                    optFloat(obj, "aspect_ratio", 2.35f),
                    optFloat(obj, "fade_in", 0.5f),
                    optFloat(obj, "fade_out", 0.5f)
            ));
        }
        return clips;
    }

    // ========== AudioClip 解析 ==========

    private static List<AudioClip> parseAudioClips(JsonArray clipsArr, String p) throws ScriptParseException {
        List<AudioClip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            JsonObject obj = clipsArr.get(i).getAsJsonObject();
            String cp = p + "[" + i + "]";
            clips.add(new AudioClip(
                    requireFloat(obj, cp, "start_time"),
                    requireFloat(obj, cp, "duration"),
                    requireString(obj, cp, "sound"),
                    optFloat(obj, "volume", 1.0f),
                    optFloat(obj, "pitch", 1.0f),
                    optBool(obj, "loop", false),
                    optFloat(obj, "fade_in", 0f),
                    optFloat(obj, "fade_out", 0f)
            ));
        }
        return clips;
    }

    // ========== EventClip 解析 ==========

    private static List<EventClip> parseEventClips(JsonArray clipsArr, String p) throws ScriptParseException {
        List<EventClip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            JsonObject obj = clipsArr.get(i).getAsJsonObject();
            String cp = p + "[" + i + "]";
            clips.add(new EventClip(
                    requireFloat(obj, cp, "start_time"),
                    requireFloat(obj, cp, "duration"),
                    requireString(obj, cp, "event_type"),
                    requireString(obj, cp, "command")
            ));
        }
        return clips;
    }

    // ========== ModEventClip 解析 ==========

    private static List<ModEventClip> parseModEventClips(JsonArray clipsArr, String p) throws ScriptParseException {
        List<ModEventClip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            JsonObject obj = clipsArr.get(i).getAsJsonObject();
            String cp = p + "[" + i + "]";

            Map<String, Object> data = new HashMap<>();
            if (obj.has("data") && obj.get("data").isJsonObject()) {
                data = parseDataMap(obj.getAsJsonObject("data"), cp + ".data");
            }

            clips.add(new ModEventClip(
                    requireFloat(obj, cp, "start_time"),
                    requireFloat(obj, cp, "duration"),
                    requireString(obj, cp, "event_type"),
                    data
            ));
        }
        return clips;
    }

    // ========== 验证方法 ==========

    private static void validateTracks(List<TimelineTrack> tracks, String p) throws ScriptParseException {
        // O2: 轨道数量限制改为警告而非错误
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

        // F1: crossfade 约束降级为警告
        for (TimelineTrack track : tracks) {
            if (track.getType() == TrackType.CAMERA) {
                List<CameraClip> clips = track.getCameraClips();
                for (int i = 1; i < clips.size(); i++) {
                    CameraClip clip = clips.get(i);
                    CameraClip prevClip = clips.get(i - 1);
                    if (clip.isCrossfade() && prevClip != null) {
                        if (prevClip.isPositionModeRelative() != clip.isPositionModeRelative()) {
                            LOGGER.warn("crossfade 相邻 clip 的 position_mode 不同（{} → {}），" +
                                    "运行时已统一为世界坐标，混合结果可能非预期",
                                    prevClip.isPositionModeRelative() ? "relative" : "absolute",
                                    clip.isPositionModeRelative() ? "relative" : "absolute");
                        }
                        if (prevClip.getInterpolation() != clip.getInterpolation()) {
                            LOGGER.warn("crossfade 相邻 clip 的 interpolation 不同（{} → {}），" +
                                    "混合结果可能产生速度跳变",
                                    prevClip.getInterpolation(), clip.getInterpolation());
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
            throw new ScriptParseException(p, "未知的轨道类型: " + value +
                    "，支持: camera/letterbox/audio/event/mod_event");
        }
    }

    private static InterpolationType parseInterpolationType(String value, String p) throws ScriptParseException {
        try {
            return InterpolationType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ScriptParseException(p, "未知的插值类型: " + value +
                    "，支持: linear/smooth/ease_in/ease_out/ease_in_out");
        }
    }

    private static InterpolationScope parseInterpolationScope(String value, String p) throws ScriptParseException {
        try {
            return InterpolationScope.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ScriptParseException(p, "未知的插值作用域: " + value +
                    "，支持: clip/segment");
        }
    }

    private static CurveCompositionMode parseCurveCompositionMode(String value, String p) throws ScriptParseException {
        try {
            return CurveCompositionMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ScriptParseException(p, "未知的曲线组合模式: " + value +
                    "，支持: override/composed");
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

    @SuppressWarnings("unchecked")
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
            } else if (val.isJsonNull()) {
                map.put(entry.getKey(), null);
            } else {
                map.put(entry.getKey(), val.toString());
            }
        }
        return map;
    }

    // ========== JSON 读取辅助（必填/可选） ==========

    private static String requireString(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key)) {
            throw new ScriptParseException(p + "." + key, "缺少必填字段");
        }
        return obj.get(key).getAsString();
    }

    private static int requireInt(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key)) {
            throw new ScriptParseException(p + "." + key, "缺少必填字段");
        }
        try {
            return obj.get(key).getAsInt();
        } catch (NumberFormatException e) {
            throw new ScriptParseException(p + "." + key, "期望整数，实际: " + obj.get(key));
        }
    }

    private static float requireFloat(JsonObject obj, String p, String key) throws ScriptParseException {
        if (!obj.has(key)) {
            throw new ScriptParseException(p + "." + key, "缺少必填字段");
        }
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
