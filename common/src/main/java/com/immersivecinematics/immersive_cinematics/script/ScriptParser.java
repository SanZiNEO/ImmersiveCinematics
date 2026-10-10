package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
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
        CinematicScript script = new CinematicScript(meta, timeline);
        // 保留原始 JSON：meta.listener 等运行期扩展字段只在 rawJson 里读取；
        // 服务端推送脚本也必须走这里，否则脚本会被当成默认 listener=player。
        script.setRawJson(json);
        return script;
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

        // 运行时行为默认值来自 SchemaRegistry.getMetaFields()（编辑器与播放器共用同一份 schema）
        boolean blockKeyboard = optBoolMeta(metaObj, "block_keyboard");
        boolean blockMouse = optBoolMeta(metaObj, "block_mouse");
        boolean blockMobAi = optBoolMeta(metaObj, "block_mob_ai");
        boolean hideHud = optBoolMeta(metaObj, "hide_hud");
        Boolean hideArm = optNullableBool(metaObj, "hide_arm");
        Boolean suppressBob = optNullableBool(metaObj, "suppress_bob");
        Boolean suppressDistortion = optNullableBool(metaObj, "suppress_distortion");
        Boolean hideChat = optNullableBool(metaObj, "hide_chat");
        Boolean hideScoreboard = optNullableBool(metaObj, "hide_scoreboard");
        Boolean hideActionBar = optNullableBool(metaObj, "hide_action_bar");
        Boolean hideTitle = optNullableBool(metaObj, "hide_title");
        Boolean hideSubtitles = optNullableBool(metaObj, "hide_subtitles");
        Boolean hideHotbar = optNullableBool(metaObj, "hide_hotbar");
        Boolean hideCrosshair = optNullableBool(metaObj, "hide_crosshair");
        Boolean hideBossbar = optNullableBool(metaObj, "hide_bossbar");
        Boolean hideSkipHud = optNullableBool(metaObj, "hide_skip_hud");
        // 强硬隐藏模式：三态，null = 未声明 → normal（不回落 hide_hud，见 CinematicController.effectiveHardHide）
        Boolean hardHideHud = optNullableBool(metaObj, "hard_hide_hud");
        boolean renderPlayerModel = optBoolMeta(metaObj, "render_player_model");
        boolean pauseWhenGamePaused = optBoolMeta(metaObj, "pause_when_game_paused");
        boolean interruptible = optBoolMeta(metaObj, "interruptible");
        boolean skippable = optBoolMeta(metaObj, "skippable");
        boolean holdAtEnd = optBoolMeta(metaObj, "hold_at_end");
        boolean macroLoop = optBoolMeta(metaObj, "macro_loop");
        // 宏观循环次数：-1 = 无限重复；正整数 N = 从 a 到 b 重复 N 圈后自然结束；0 无意义 → 按 1（与 clip.loop_count 同口径）
        int macroLoopCount = optInt(metaObj, "macro_loop_count", -1);
        if (macroLoopCount == 0) {
            ErrorLog.log("Parse", p + ".macro_loop_count 不允许为 0（1 = 只播一遍），按 1 处理");
            macroLoopCount = 1;
        } else if (macroLoopCount < -1) {
            ErrorLog.log("Parse", p + ".macro_loop_count 只能为 -1（无限重复）或正整数，实际: "
                    + macroLoopCount + "，按 -1 处理");
            macroLoopCount = -1;
        }
        // 宏观循环模式：枚举 repeat / pingpong，默认 repeat；非法值 → 告警并按 repeat
        String macroLoopMode = optString(metaObj, "macro_loop_mode", "repeat");
        if (!"repeat".equals(macroLoopMode) && !"pingpong".equals(macroLoopMode)) {
            ErrorLog.log("Parse", p + ".macro_loop_mode 非法值: " + macroLoopMode + "，按 repeat 处理");
            macroLoopMode = "repeat";
        }
        java.util.Map<String, Boolean> hudLayers = parseHudLayers(metaObj);
        // 编辑基准分辨率（可选）：{w, h} 正整数；缺省 1920×1080，非法 → 告警 + 回落缺省（防御式）
        int[] baseResolution = parseBaseResolution(metaObj);

        ScriptMeta.RuntimeBehavior behavior = new ScriptMeta.RuntimeBehavior(
                blockKeyboard, blockMouse, blockMobAi,
                hideHud, hideArm, suppressBob, suppressDistortion,
                hideChat, hideScoreboard, hideActionBar,
                hideTitle, hideSubtitles, hideHotbar, hideCrosshair,
                hideBossbar, hideSkipHud,
                hardHideHud,
                renderPlayerModel,
                pauseWhenGamePaused, interruptible, skippable,
                holdAtEnd, macroLoop, macroLoopMode, macroLoopCount, hudLayers);

        // 播放优先级（默认值来自 SchemaRegistry.getMetaFields()；仅用于队列内排序）
        int priority = optInt(metaObj, "priority", 0);

        // 跳过投票比例（可选，10~100）：缺省/非法 → null，运行时回落到全局配置 Config.skipVoteRatio
        Integer skipVoteRatio = optNullableInt(metaObj, "skip_vote_ratio");
        if (skipVoteRatio != null && (skipVoteRatio < 10 || skipVoteRatio > 100)) {
            ErrorLog.log("Parse", p + ".skip_vote_ratio 超出范围 10~100，实际: " + skipVoteRatio
                    + "，已忽略（使用全局配置）");
            skipVoteRatio = null;
        }

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

        return new ScriptMeta(id, name, author, version, description, behavior, priority, dimension, triggers, skipVoteRatio,
                baseResolution[0], baseResolution[1]);
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

        // 宏观循环区间 [a, b]（meta.macro_loop 的参数）：loop_start 缺省 0；loop_end 缺省 -1 = 未声明（用宏观末端）
        float loopStart = optFloat(timelineObj, "loop_start", 0f);
        float loopEnd = optFloat(timelineObj, "loop_end", -1f);

        validateTracks(tracks, p);
        return new Timeline(totalDuration, tracks, loopStart, loopEnd);
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
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getClipFields(type).entrySet()) {
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

        // 关键帧级统一设计（2026-08-09 决定）：所有轨道一律以 keyframes 调控，
        // 不再提供 clip 级简写/旧格式兼容（letterbox 的 clip 级 aspect_ratio 简写、
        // EVENT 的 clip 级 command 迁移等遗留兼容已删除）——旧格式脚本会因缺 keyframes
        // 校验失败，需按关键帧形式改写。

        // 验证
        if (type == TrackType.CAMERA && keyframes.isEmpty()) {
            throw new ScriptParseException(p, "camera clip 的 keyframes 至少1个");
        }
        if (duration == 0f) {
            throw new ScriptParseException(p + ".duration", "不允许为0，正数=有限时长，负数=无限时长，实际: " + duration);
        }
        // loop_count=0 非法：-1=无限循环，正整数=循环次数；记录错误并按 1 处理（不阻断运行）
        Object loopCount = data.get("loop_count");
        if (loopCount instanceof Number && ((Number) loopCount).intValue() == 0) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Parse",
                    p + ".loop_count 不允许为 0（-1=无限循环，正整数=循环次数），已按 1 处理");
            data.put("loop_count", 1);
        }
        if (data.get("curve") instanceof BezierCurve curve && !curve.isValid()) {
            throw new ScriptParseException(p + ".curve", "control_points 必须恰好2个点");
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
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getKeyframeFields(type).entrySet()) {
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
        FieldDef def = isKeyframe
                ? SchemaLoader.getKeyframeFields(type).get(fieldName)
                : SchemaLoader.getClipFields(type).get(fieldName);

        // LUT 文件名（clip 级 lut；只有声明该字段的轨道 = ADJUST 轨）：schema 里是 string，
        // 但额外拦路径分隔符（见 parseLutFile）——其余轨道上同名字段走下方「未知字段」的向前兼容路径
        if (!isKeyframe && "lut".equals(fieldName) && def != null) {
            return parseLutFile(value, p + "." + fieldName);
        }

        // LUT 输入域适配（clip 级 lut_input_gamma；同样只有声明该字段的轨道 = ADJUST 轨）：
        // schema 里是 float，但额外拦 ≤ 0（见 parseLutInputGamma）——其余轨道上同名字段走下方「未知字段」路径
        if (!isKeyframe && "lut_input_gamma".equals(fieldName) && def != null) {
            return parseLutInputGamma(value, p + "." + fieldName);
        }

        // 混合模式（clip 级 blend_mode；同样只有声明该字段的轨道 = ADJUST 轨）：schema 里是 enum，
        // 但额外拦非法枚举值（见 parseBlendMode）——其余轨道上同名字段走下方「未知字段」路径
        if (!isKeyframe && "blend_mode".equals(fieldName) && def != null) {
            return parseBlendMode(value, p + "." + fieldName);
        }

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
            case "string", "enum" -> value.getAsString();
            case "bool" -> value.getAsBoolean();
            case "position" -> {
                if (!value.isJsonObject()) throw new ScriptParseException(p + "." + fieldName, "position 需要 JSON 对象");
                // 从 clip 级无法直接获取 position_mode，按实际 JSON 推断：
                // 有 dx（世界轴相对）或有 fwd/up/right（基准空间相对）= 相对；有 x/y/z = 绝对
                JsonObject posObj = value.getAsJsonObject();
                boolean relative = posObj.has("dx") || posObj.has("fwd") || posObj.has("up") || posObj.has("right");
                yield parsePositionData(posObj, p + "." + fieldName, relative);
            }
            case "bezier_curve" -> parseBezierCurve(value.getAsJsonObject(), p + "." + fieldName);
            case "color_curve" -> parseColorCurve(value, p + "." + fieldName);
            case "map" -> {
                if (value.isJsonObject()) {
                    yield parseDataMap(value.getAsJsonObject(), p + "." + fieldName);
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 解析 clip 级 {@code lut}（LUT 文件名，如 {@code "Teal and Orange.cube"}；<b>ADJUST 轨专用</b>——
     * LUT 是对<b>整体画面</b>（master）的烘焙，逐 lane / 相机片段不参与）。
     * <p>LUT 只从 {@code resource/} 目录取（{@link com.immersivecinematics.immersive_cinematics.util.ResourcePath}），
     * 故文件名必须是<b>非空</b>且<b>不含路径分隔符</b>（{@code /} / {@code \}）或盘符分隔符（{@code :}）的
     * 单个文件名——{@code ../} 之类穿越、绝对路径、Windows 盘符相对路径（{@code C:foo}）一律拒绝
     * （与 {@code ScriptValidator#checkLutFile} 同一口径：校验拦下的写法解析期也会拒绝）。</p>
     */
    private static String parseLutFile(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(p, "需要字符串（resource/ 目录下的 .cube 文件名）");
        }
        String name = value.getAsString();
        if (name.isBlank()) {
            throw new ScriptParseException(p, "文件名不能为空");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new ScriptParseException(p, "文件名不能包含路径分隔符（LUT 只取 resource/ 目录下的文件）：" + name);
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new ScriptParseException(p, "文件名非法：" + name);
        }
        return name;
    }

    /**
     * 解析 clip 级 {@code lut_input_gamma}（LUT 输入域适配的幂指数；<b>ADJUST 轨专用</b>——
     * LUT 是对<b>整体画面</b>（master）的烘焙，逐 lane / 相机片段不参与）。
     * <p>语义：查表前对输入 RGB 逐通道 {@code v = pow(clamp(c, 0, 1), lut_input_gamma)}，
     * 以 {@code v} 为四面体查表坐标；缺省 1 = 不变换。必须 {@code > 0}（0 会让整个输入域塌到 1、
     * 负数在 {@code c = 0} 处发散——解析期直接拒绝，与 {@code ScriptValidator#checkLutInputGamma}
     * 同一口径：校验拦下的写法解析期也会拒绝）。{@code NaN} 同样落在这里被拒（{@code !(v > 0)}）。</p>
     */
    private static Float parseLutInputGamma(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(p, "需要数字（必须大于 0；缺省 1 = 不变换）");
        }
        float gamma = value.getAsFloat();
        if (!Float.isFinite(gamma) || !(gamma > 0.0F)) {
            throw new ScriptParseException(p, "必须大于 0 的有限数字（缺省 1 = 不变换）：" + gamma);
        }
        return gamma;
    }

    /**
     * 解析 clip 级 {@code blend_mode}（混合模式；<b>ADJUST 轨专用</b>——混合模式作用于<b>调整层</b>
     * （= ADJUST 轨）的输出与基画面（{@code Sampler0} 原图）的层级混合，逐 lane / 相机片段不参与）。
     * <p>合法值 = {@code normal} / {@code multiply} / {@code screen} / {@code soft_light} /
     * {@code overlay}（缺省 {@code normal} = 直替换 = 现状）；其余值解析期直接拒绝
     * （与 {@code ScriptValidator} 的枚举校验同一口径：校验拦下的写法解析期也会拒绝）。</p>
     */
    private static String parseBlendMode(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(p,
                    "需要字符串（normal / multiply / screen / soft_light / overlay；缺省 normal）");
        }
        String mode = value.getAsString();
        switch (mode) {
            case "normal", "multiply", "screen", "soft_light", "overlay" -> {
                return mode;
            }
            default -> throw new ScriptParseException(p, "未知混合模式：" + mode
                    + "（合法值：normal / multiply / screen / soft_light / overlay）");
        }
    }

    // ========== BezierCurve 解析 ==========

    private static BezierCurve parseBezierCurve(JsonObject obj, String p) throws ScriptParseException {
        String type = optString(obj, "type", "bezier");
        JsonArray cpArr = requireArray(obj, p, "control_points");
        if (cpArr.size() != 2) {
            throw new ScriptParseException(p + ".control_points", "必须恰好2个控制点，实际: " + cpArr.size());
        }
        List<BezierCurve.ControlPoint> controlPoints = new ArrayList<>();
        for (int i = 0; i < cpArr.size(); i++) {
            JsonObject cp = cpArr.get(i).getAsJsonObject();
            String pp = p + ".control_points[" + i + "]";
            // 自描述：有 dx/dy/dz = 相对段起点偏移；有 x/y/z = 世界绝对坐标
            if (cp.has("dx")) {
                controlPoints.add(BezierCurve.ControlPoint.relative(
                        requireFloat(cp, pp, "dx"),
                        requireFloat(cp, pp, "dy"),
                        requireFloat(cp, pp, "dz")));
            } else {
                controlPoints.add(BezierCurve.ControlPoint.absolute(
                        requireFloat(cp, pp, "x"),
                        requireFloat(cp, pp, "y"),
                        requireFloat(cp, pp, "z")));
            }
        }
        return new BezierCurve(type, controlPoints);
    }

    // ========== ColorCurve 解析（ADJUST 轨曲线组，形态 b）==========

    /**
     * 解析 {@code color_curve} 字段 = 控制点数组 {@code [[x,y], ...]}
     * （ADJUST 轨 / CAMERA 片段的 RGB 复合曲线 {@code rgb_curve} 与每通道曲线 {@code r_curve} / {@code g_curve} / {@code b_curve}
     * 共用本解析；字段名由 {@code p} 带出）。
     * <p>结构在此严格校验（与 {@code ScriptValidator} 同一口径）：数组、每点 2 个数字、x 严格递增、
     * x / y 各 0~1、至少 2 点——不合法直接抛 {@link ScriptParseException}（不是静默忽略：
     * 曲线是作者显式写下的意图，悄悄丢掉会得到「脚本没错但画面不对」）。</p>
     */
    private static ColorCurve parseColorCurve(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonArray()) {
            throw new ScriptParseException(p, "需要控制点数组 [[x,y], ...]");
        }
        JsonArray arr = value.getAsJsonArray();
        if (arr.size() < 2) {
            throw new ScriptParseException(p, "至少需要 2 个控制点，实际: " + arr.size());
        }
        List<ColorCurve.Point> points = new ArrayList<>();
        float prevX = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.size(); i++) {
            String pp = p + "[" + i + "]";
            JsonElement e = arr.get(i);
            if (!e.isJsonArray() || e.getAsJsonArray().size() != 2) {
                throw new ScriptParseException(pp, "控制点需要 [x, y] 两个数字");
            }
            JsonArray pt = e.getAsJsonArray();
            if (!pt.get(0).isJsonPrimitive() || !pt.get(0).getAsJsonPrimitive().isNumber()
                    || !pt.get(1).isJsonPrimitive() || !pt.get(1).getAsJsonPrimitive().isNumber()) {
                throw new ScriptParseException(pp, "控制点需要 [x, y] 两个数字");
            }
            float x = pt.get(0).getAsFloat();
            float y = pt.get(1).getAsFloat();
            if (x < 0f || x > 1f || y < 0f || y > 1f) {
                throw new ScriptParseException(pp, "控制点超出 0~1：" + x + ", " + y);
            }
            if (x <= prevX) {
                throw new ScriptParseException(pp, "控制点 x 必须严格递增（前一个 x = " + prevX + "，当前 = " + x + "）");
            }
            prevX = x;
            points.add(new ColorCurve.Point(x, y));
        }
        return new ColorCurve(points);
    }

    // ========== PositionData 解析 ==========

    private static PositionData parsePositionData(JsonObject obj, String p, boolean positionModeRelative) throws ScriptParseException {
        if (positionModeRelative) {
            // 基准空间坐标系偏移（fwd/up/right 相对基准朝向，仅实体/玩家基准）
            if (obj.has("fwd") || obj.has("up") || obj.has("right")) {
                return parseFacingRelative(obj, p);
            }
            if (!obj.has("dx")) {
                throw new ScriptParseException(p, "relative 模式需要 dx/dy/dz（世界轴偏移）或 fwd/up/right（基准空间偏移）字段");
            }
            float dx = requireFloat(obj, p, "dx");
            float dy = requireFloat(obj, p, "dy");
            float dz = requireFloat(obj, p, "dz");
            return parseRelativeWithOrigin(obj, p, dx, dy, dz);
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

    /**
     * 基准空间坐标系偏移：fwd/up/right 相对"基准点 + 基准朝向"的偏移。
     * <p>
     * 基准点来源（可选，按优先级）：
     * <ol>
     *   <li>{@code facing_origin} = 实体选择器 → 基准点 = 该实体（每帧求值）</li>
     *   <li>{@code facing_origin: "coordinate"} + {@code facing_origin_x/y/z} → 固定世界坐标</li>
     *   <li>不写 → 回落旧行为：follow 实体（follow=entity 时）或玩家</li>
     * </ol>
     * 基准朝向 = 基准点自身朝向（实体 = 身体 yaw + 视线 pitch；玩家 = 实时视线）。
     * <p>
     * 与 {@code dx/dy/dz}（世界轴偏移）互斥：一个 position 只能选一套偏移轴。
     */
    private static PositionData parseFacingRelative(JsonObject obj, String p) throws ScriptParseException {
        if (obj.has("dx") || obj.has("dy") || obj.has("dz")) {
            throw new ScriptParseException(p,
                    "fwd/up/right（基准空间偏移）不能与 dx/dy/dz（世界轴偏移）混用");
        }
        float fwd = optFloat(obj, "fwd", 0f);
        float up = optFloat(obj, "up", 0f);
        float right = optFloat(obj, "right", 0f);
        String upAxis = optString(obj, "up_axis", "view");
        if (!"view".equals(upAxis) && !"world".equals(upAxis)) {
            throw new ScriptParseException(p + ".up_axis", "仅支持 view（up随俯仰）/ world（up保持竖直），实际: " + upAxis);
        }

        // 显式指定的基准点（新能力）；不写则回落旧行为（follow 实体 / 玩家）
        String facingTarget = optString(obj, "facing_target", "");
        if (obj.has("facing_origin")) {
            OriginSpec spec = parseOriginSpec(obj, "facing_origin", p);
            if (spec.isCoordinate()) {
                return PositionData.facingToCoordinate(fwd, up, right, upAxis,
                        requireFloat(obj, p, "facing_origin_x"),
                        requireFloat(obj, p, "facing_origin_y"),
                        requireFloat(obj, p, "facing_origin_z"))
                        .withFacingTarget(facingTarget);
            }
            if (spec.isPlayer()) {
                return PositionData.facingToEntity("@p", fwd, up, right, upAxis)
                        .withFacingTarget(facingTarget);
            }
            String origin = spec.selector;
            if (origin != null && origin.startsWith("block:")) {
                // block:id 或 block:id:radius
                String[] parsed = PositionData.parseBlockOriginString(origin);
                return PositionData.facingToBlock(fwd, up, right, upAxis, parsed[0], Integer.parseInt(parsed[1]))
                        .withFacingTarget(facingTarget);
            }
            if (origin != null && (origin.startsWith("#") || origin.contains(":")) && !origin.startsWith("@")) {
                // 形如 minecraft:village 的结构 id（结构中心）
                return PositionData.facingToStructure(fwd, up, right, upAxis, origin)
                        .withFacingTarget(facingTarget);
            }
            if (origin != null) {
                return PositionData.facingToEntity(origin, fwd, up, right, upAxis)
                        .withFacingTarget(facingTarget);
            }
            throw new ScriptParseException(p + ".facing_origin",
                    "暂不支持该基准点类型（支持实体选择器 / \"coordinate\" / block:id[:radius] / 结构 id）");
        }

        // 兼容旧写法：relative_origin 显式写成 "player" 时明确走玩家基准
        if (obj.has("relative_origin")) {
            OriginSpec spec = parseOriginSpec(obj, "relative_origin", p);
            if (spec.isPlayer()) {
                return PositionData.facingToEntity("@p", fwd, up, right, upAxis);
            }
            throw new ScriptParseException(p + ".relative_origin",
                    "fwd/up/right 的基准点请用 facing_origin（relative_origin 仅用于 dx/dy/dz 世界轴偏移）");
        }
        return PositionData.facingToEntity("@p", fwd, up, right, upAxis);
    }

    /** 基准点描述（点源清单的统一解析结果） */
    private static final class OriginSpec {
        static final int PLAYER = 0;
        static final int COORDINATE = 1;
        static final int SELECTOR = 2;

        int kind = PLAYER;
        String selector;

        boolean isPlayer() { return kind == PLAYER; }
        boolean isCoordinate() { return kind == COORDINATE; }
    }

    /**
     * 解析基准点字段（relative_origin / facing_origin 共用）：
     * {@code "coordinate"} = 固定坐标（配 {@code <field>_x/y/z}）；
     * {@code "player"} / {@code "@p"} / {@code "@s"} = 玩家；
     * 其他字符串 = 实体选择器。
     *
     * @param fieldName 原始字段名，用于拼出 {@code <fieldName>_x} 这类配套字段
     */
    private static OriginSpec parseOriginSpec(JsonObject posObj, String fieldName, String p) throws ScriptParseException {
        OriginSpec spec = new OriginSpec();
        JsonElement el = posObj.get(fieldName);
        if (!el.isJsonPrimitive()) {
            throw new ScriptParseException(p + "." + fieldName, fieldName + " 只支持字符串写法");
        }
        String value = el.getAsString();
        if ("coordinate".equals(value)) {
            spec.kind = OriginSpec.COORDINATE;
        } else if ("player".equals(value) || "@p".equals(value) || "@s".equals(value)) {
            spec.kind = OriginSpec.PLAYER;
        } else {
            spec.kind = OriginSpec.SELECTOR;
            spec.selector = value;
        }
        return spec;
    }

    /**
     * 世界轴相对偏移（dx/dy/dz）的基准点解析：relative_origin 字段可选——
     * 缺省 = 玩家激活位置；"coordinate" = 相对固定坐标（relative_origin_x/y/z）；
     * "block:id[:radius]" 或 {type:"block",block,radius} = 玩家附近搜索的方块；其他字符串 = 结构 id（相对结构中心）。
     */
    private static PositionData parseRelativeWithOrigin(JsonObject posObj, String p, float dx, float dy, float dz) throws ScriptParseException {
        if (!posObj.has("relative_origin")) {
            return PositionData.relative(dx, dy, dz);
        }
        JsonElement originEl = posObj.get("relative_origin");
        if (originEl.isJsonObject()) {
            // 结构化对象：{ "type": "block", "block": "minecraft:obsidian", "radius": 32 }
            JsonObject o = originEl.getAsJsonObject();
            String type = requireString(o, p + ".relative_origin", "type");
            if (!"block".equals(type)) {
                throw new ScriptParseException(p + ".relative_origin", "对象写法目前仅支持 type=block");
            }
            String blockId = requireString(o, p + ".relative_origin", "block");
            int radius = o.has("radius") ? o.get("radius").getAsInt() : PositionData.DEFAULT_BLOCK_RADIUS;
            if (radius <= 0) {
                throw new ScriptParseException(p + ".relative_origin.radius", "radius 必须为正整数");
            }
            return PositionData.relativeToBlock(dx, dy, dz, blockId, radius);
        }
        OriginSpec spec = parseOriginSpec(posObj, "relative_origin", p);
        if (spec.isCoordinate()) {
            return PositionData.relativeToCoordinate(dx, dy, dz,
                    requireFloat(posObj, p, "relative_origin_x"),
                    requireFloat(posObj, p, "relative_origin_y"),
                    requireFloat(posObj, p, "relative_origin_z"));
        }
        if (spec.isPlayer()) {
            return PositionData.relative(dx, dy, dz);
        }
        String origin = spec.selector;
        if (origin.startsWith("block:")) {
            // block:id 或 block:id:radius
            String[] parsed = PositionData.parseBlockOriginString(origin);
            return PositionData.relativeToBlock(dx, dy, dz, parsed[0], Integer.parseInt(parsed[1]));
        }
        // 其他字符串视为结构 id（相对结构中心；服务端推送前会解析为坐标，客户端预览自行定位）
        return PositionData.relativeToStructure(dx, dy, dz, origin);
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
        List<TriggerRequirement> requires = parseTriggerRequires(obj, p);
        return new TriggerDefinition(type, conditions, repeatable, delay, onEnter, exitBuffer, requires);
    }

    /**
     * 解析触发器前置条件 requires（AND 语义；缺省 = 无前置，兼容旧脚本）。
     * <ul>
     *   <li>旧语法：字符串 = 前置脚本 id，等价于 {@code {"type":"script_played","script":"id"}}</li>
     *   <li>新语法：对象 = {@code {"type":"...", ...}}，可注册自定义前置条件</li>
     * </ul>
     */
    private static List<TriggerRequirement> parseTriggerRequires(JsonObject obj, String p) throws ScriptParseException {
        if (!obj.has("requires")) return Collections.emptyList();
        if (!obj.get("requires").isJsonArray()) {
            throw new ScriptParseException(p + ".requires", "requires 必须是数组");
        }
        List<TriggerRequirement> result = new ArrayList<>();
        JsonArray arr = obj.getAsJsonArray("requires");
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            String rp = p + ".requires[" + i + "]";
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String id = el.getAsString();
                if (id.isEmpty()) {
                    throw new ScriptParseException(rp, "前置脚本 id 不能为空");
                }
                result.add(TriggerRequirement.scriptPlayed(id));
            } else if (el.isJsonObject()) {
                JsonObject reqObj = el.getAsJsonObject();
                if (!reqObj.has("type") || !reqObj.get("type").isJsonPrimitive() || !reqObj.get("type").getAsJsonPrimitive().isString()) {
                    throw new ScriptParseException(rp, "对象型前置条件必须包含字符串 type");
                }
                String type = reqObj.get("type").getAsString();
                if (type.isEmpty()) {
                    throw new ScriptParseException(rp, "前置条件 type 不能为空");
                }
                JsonObject data = new JsonObject();
                for (var entry : reqObj.entrySet()) {
                    if (!"type".equals(entry.getKey())) {
                        data.add(entry.getKey(), entry.getValue());
                    }
                }
                result.add(new TriggerRequirement(type, data));
            } else {
                throw new ScriptParseException(rp, "requires 元素必须是字符串脚本 id 或对象型前置条件");
            }
        }
        return result;
    }

    // ========== 验证方法 ==========

    private static void validateTracks(List<TimelineTrack> tracks, String p) throws ScriptParseException {
        long letterboxCount = tracks.stream().filter(t -> t.getType() == TrackType.LETTERBOX).count();
        if (letterboxCount > 1) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Parse",
                    "检测到 " + letterboxCount + " 条 LETTERBOX 轨道，建议最多1条");
        }
        long eventCount = tracks.stream().filter(t -> t.getType() == TrackType.EVENT).count();
        if (eventCount > 1) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Parse",
                    "检测到 " + eventCount + " 条 EVENT 轨道，建议最多1条");
        }

        for (TimelineTrack track : tracks) {
            if (track.getType() == TrackType.CAMERA) {
                List<Clip> clips = track.getClips();
                for (int i = 1; i < clips.size(); i++) {
                    Clip clip = clips.get(i);
                    Clip prevClip = clips.get(i - 1);
                    if (clip.isMorph() && prevClip != null) {
                        if (prevClip.isPositionModeRelative() != clip.isPositionModeRelative()) {
                            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Parse",
                                    "morph 相邻 clip 的 position_mode 不同（" + (prevClip.isPositionModeRelative() ? "relative" : "absolute")
                                            + " → " + (clip.isPositionModeRelative() ? "relative" : "absolute")
                                            + "），运行时已统一为世界坐标，混合结果可能非预期");
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
            throw new ScriptParseException(p, "未知的轨道类型: " + value
                    + "，支持: camera/letterbox/audio/event/mod_event/overlay/adjust");
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

    /** 可选整数：缺省 / JSON null / 非数字 → null */
    private static Integer optNullableInt(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
        return el.getAsInt();
    }

    /**
     * 解析 {@code meta.base_resolution}（可选）：编辑基准分辨率 {@code {w, h}}（像素，正整数），
     * 缺省 {@link ScriptMeta#DEFAULT_BASE_WIDTH}×{@link ScriptMeta#DEFAULT_BASE_HEIGHT}；
     * 非对象 / 缺分量 / 非正整数 → 告警 + 回落缺省（防御式，与 {@code macro_loop_count} 同口径）。
     *
     * @return {@code {宽, 高}}（始终是正整数）
     */
    private static int[] parseBaseResolution(JsonObject metaObj) {
        int w = ScriptMeta.DEFAULT_BASE_WIDTH;
        int h = ScriptMeta.DEFAULT_BASE_HEIGHT;
        if (!metaObj.has("base_resolution") || metaObj.get("base_resolution").isJsonNull()) {
            return new int[] {w, h};
        }
        JsonElement el = metaObj.get("base_resolution");
        if (!el.isJsonObject()) {
            ErrorLog.log("Parse", "meta.base_resolution 需要对象 {w, h}（像素，正整数），实际: " + el
                    + "，按缺省 " + w + "×" + h + " 处理");
            return new int[] {w, h};
        }
        JsonObject obj = el.getAsJsonObject();
        Integer pw = positiveIntComponent(obj, "w");
        Integer ph = positiveIntComponent(obj, "h");
        if (pw == null || ph == null) {
            ErrorLog.log("Parse", "meta.base_resolution 需要正整数 w / h，实际: " + obj
                    + "，按缺省 " + w + "×" + h + " 处理");
            return new int[] {w, h};
        }
        return new int[] {pw, ph};
    }

    /** 读正整数分量：字段缺失 / 非数字 / 非整数 / ≤ 0 / 超出 int 范围 → null */
    private static Integer positiveIntComponent(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d != Math.floor(d) || d <= 0 || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    /** 解析 meta.hud_layers：模组/自定义 HUD 层的显隐覆盖（true=隐藏，false=显示） */
    private static java.util.Map<String, Boolean> parseHudLayers(JsonObject metaObj) {
        java.util.Map<String, Boolean> map = new java.util.LinkedHashMap<>();
        if (!metaObj.has("hud_layers") || !metaObj.get("hud_layers").isJsonObject()) {
            return map;
        }
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : metaObj.getAsJsonObject("hud_layers").entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                map.put(e.getKey(), e.getValue().getAsBoolean());
            }
        }
        return map;
    }

    private static boolean optBool(JsonObject obj, String key, boolean defaultVal) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : defaultVal;
    }

    /** meta 字段默认值来自 SchemaRegistry.getMetaFields()（编辑器与播放器共用同一份 schema） */
    private static boolean optBoolMeta(JsonObject obj, String key) {
        if (obj.has(key)) return obj.get(key).getAsBoolean();
        FieldDef def = SchemaLoader.getMetaFields().get(key);
        if (def != null && def.defaultValue() instanceof Boolean) return (Boolean) def.defaultValue();
        return false;
    }

    /**
     * 读取可空 Boolean：字段不存在或为 JsonNull 时返回 null
     */
    private static Boolean optNullableBool(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsBoolean();
    }
}
