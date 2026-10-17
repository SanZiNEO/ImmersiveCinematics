package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ComposeMode;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionInterrupt;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionMode;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionParams;
import com.immersivecinematics.immersive_cinematics.math.MathModel;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import com.immersivecinematics.immersive_cinematics.script.schema.MathModelRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本解析器 — 将 JSON 字符串解析为 CinematicScript POJO
 * <p>
 * 使用 Gson 的 JsonElement 树 API 手动解析，而非反射绑定。
 * 通过 {@link SchemaLoader} 驱动字段解析，不再为每种轨道类型编写独立解析方法。
 */
public class ScriptParser {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptParser");

    /** {@code position.up_axis} 的移除口径（0.3.6 起基准坐标系上轴固定世界竖直，方向侧改用锁字段）。 */
    private static final String REMOVED_UP_AXIS = "已移除（0.3.6 起）：基准坐标系的上轴固定为世界竖直"
            + "（前轴 / 右轴取水平面内分量）；方向侧每轴跟来源或锁值改用 yaw_lock / pitch_lock"
            + "（写法见 docs/SCRIPT_FORMAT.md §「方向锁」）";

    /**
     * 模型定义驻留表：键 = 定义对象的归一形态（{@code type} + 参数键值 + 通道域，见 {@link #parseMathModel}）。
     *
     * 同（type + 参数键值）的 JSON 定义复用同一定义对象，于是「定义对象同一」= 「定义不变」——
     * 调用方（{@code camera/source/LaneMotionStepper}）据此判定片段切换时模型实例延续 / 重建；
     * 定义不可变、可被多个调用点共享（实例仍按调用点各建一份）。
     * 键为解析期一次建成的只读 map（入库后不再变更）；条目数 = 出现过的定义形态数。
     */
    private static final Map<Map<String, Object>, MathModel> MODEL_DEFINITIONS = new ConcurrentHashMap<>();

    /**
     * fade 高级颜色遮罩的 clip 级字段（0.3.6 D4–D7）：只属于 {@code layer_type=fade}。
     * {@code layer_type=image / subtitle} 上写 → 解析期明确拒绝（不静默存进 data 宣称生效）。
     */
    private static final Set<String> FADE_ONLY_CLIP_FIELDS =
            Set.of("gradient", "mask_shape", "blend_mode");

    /** fade 高级颜色遮罩的关键帧字段：只属于 {@code layer_type=fade}（image / subtitle 上写同样拒绝）。 */
    private static final Set<String> FADE_ONLY_KEYFRAME_FIELDS = Set.of(
            "color", "mask_x", "mask_y", "mask_width", "mask_height", "mask_radius",
            "gradient_angle", "gradient_center_x", "gradient_center_y", "gradient_radius");

    /**
     * CAMERA lane 合成遮罩的几何关键帧字段（0.3.6 D2）：只属于关键帧。
     * clip 级只允许形状 {@code mask_shape}；写错层级 / 写错轨道都明确拒绝。
     */
    private static final Set<String> CAMERA_MASK_GEOMETRY_FIELDS =
            Set.of("mask_x", "mask_y", "mask_width", "mask_height", "mask_radius");

    /** fade 渐变专用字段：CAMERA 片段 / 关键帧上写 = 明确拒绝（CAMERA 遮罩只有形状 + 几何）。 */
    private static final Set<String> FADE_GRADIENT_FIELDS =
            Set.of("gradient", "gradient_angle", "gradient_center_x", "gradient_center_y", "gradient_radius");

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
        // 跨脚本播放关系：枚举 parallel / exclusive / interruptible，缺省 parallel（= 现状）；非法值 → 告警并按 parallel
        String playModeRaw = optString(metaObj, "play_mode", ScriptMeta.PlayMode.PARALLEL.id());
        if (!ScriptMeta.PlayMode.isKnown(playModeRaw)) {
            ErrorLog.log("Parse", p + ".play_mode 非法值: " + playModeRaw + "，按 parallel 处理");
        }
        ScriptMeta.PlayMode playMode = ScriptMeta.PlayMode.fromString(playModeRaw);
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

        // 脚本级轮询间隔（可选，tick，1~600）：该脚本全部轮询类触发器的缺省覆盖；缺省/非法 → null
        Integer triggerPollInterval = optPollInterval(metaObj, "trigger_poll_interval", p);

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

        return new ScriptMeta(id, name, author, version, description, behavior, priority, playMode, dimension, triggers, skipVoteRatio,
                triggerPollInterval, baseResolution[0], baseResolution[1]);
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

        // OVERLAY 的 fade 专用字段（高级颜色遮罩）按 layer_type 分派：fade 走严格解析，
        // image / subtitle 上写 = 明确拒绝（不静默存进 data 宣称生效）
        String overlayLayerType = type == TrackType.OVERLAY ? overlayLayerType(obj) : null;
        boolean fadeOverlay = type == TrackType.OVERLAY
                && !"image".equals(overlayLayerType) && !"subtitle".equals(overlayLayerType);

        // 解析类型特有字段到 data map
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String fieldName = entry.getKey();
            if ("start_time".equals(fieldName) || "duration".equals(fieldName) || "keyframes".equals(fieldName)) {
                continue; // 通用字段跳过
            }
            if (type == TrackType.OVERLAY) {
                if (fadeOverlay) {
                    // 归属交叉检查：mask_* / gradient_* 数值通道只属于关键帧（color 两级都合法）
                    if (isFadeKeyframeOnlyField(fieldName)) {
                        throw new ScriptParseException(p + "." + fieldName,
                                "只属于关键帧（数值通道必须写在 keyframes 元素里，clip 级不生效）");
                    }
                    Object maskValue = parseFadeClipField(fieldName, entry.getValue(), p);
                    if (maskValue != null) {
                        data.put(fieldName, maskValue);
                        continue;
                    }
                } else if (FADE_ONLY_CLIP_FIELDS.contains(fieldName) || isFadeKeyframeOnlyField(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "fade 专用字段（当前 layer_type=" + overlayLayerType
                                    + "）：高级颜色遮罩只属于 layer_type=fade");
                }
            }
            if (type == TrackType.CAMERA) {
                // 归属交叉检查（0.3.6 D2）：形状只属于 clip 级，几何只属于关键帧；fade 渐变 / 混合模式
                // 不因新增遮罩字段而被 CAMERA 接受（遮罩只有形状 + 几何，没有颜色层语义）。
                if (CAMERA_MASK_GEOMETRY_FIELDS.contains(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "只属于关键帧（mask_x / mask_y / mask_width / mask_height / mask_radius"
                                    + " 必须写在 keyframes 元素里，clip 级不生效）");
                }
                if (FADE_GRADIENT_FIELDS.contains(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "fade 专用（gradient / gradient_* 只属于 OVERLAY 的 layer_type=fade）："
                                    + "CAMERA lane 遮罩用 clip 级 mask_shape + 关键帧 mask_* 几何");
                }
                if ("blend_mode".equals(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "不支持（混合模式只写在 ADJUST 轨片段的 clip 级；fade 的高级遮罩只属于"
                                    + " OVERLAY 的 layer_type=fade）");
                }
            }
            Object value = parseFieldBySchema(fieldName, entry.getValue(), p, type, false, null);
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
                keyframes.add(parseKeyframe(kfArr.get(i).getAsJsonObject(), p + ".keyframes[" + i + "]",
                        type, fadeOverlay, overlayLayerType));
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

        Clip clip = new Clip(startTime, duration, type, data, keyframes);
        if (fadeOverlay) {
            // fade 片段构建后立即建立 ColorMaskSpec（懒缓存首次访问）：结构 / 颜色非法在此转成
            // 带完整字段路径的解析错误，绝不把非法输入默默变黑
            try {
                clip.getColorMaskSpec();
            } catch (ColorMaskSpec.InvalidColorMaskException e) {
                throw new ScriptParseException(p + "." + e.getFieldPath(), e.getMessage());
            }
        }
        return clip;
    }
    // ========== Keyframe 解析（统一 schema 驱动）==========

    private static Keyframe parseKeyframe(JsonObject obj, String p, TrackType type,
                                          boolean fadeOverlay, String overlayLayerType)
            throws ScriptParseException {
        float time = requireFloat(obj, p, "time");
        if (time < 0) {
            throw new ScriptParseException(p + ".time", "不能为负数: " + time);
        }
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String fieldName = entry.getKey();
            if ("time".equals(fieldName)) continue;
            if ("look_at_target".equals(fieldName)) {
                // 与 ScriptValidator 的移除报错同一口径：校验拦下的写法解析期也拒绝
                throw new ScriptParseException(p + ".look_at_target",
                        "已移除（0.3.6 起）：注视目标统一为点源 + 偏移——实体用 look_at_selector"
                                + "（部位百分比 look_at_part）、坐标用 look_at_target_x/y/z、结构用"
                                + " look_at_target_structure、方块用 look_at_target_block，偏移用 look_at_offset"
                                + "（世界轴 dx/dy/dz / 基准坐标系 fwd/up/right）");
            }
            if (type == TrackType.OVERLAY) {
                if (fadeOverlay) {
                    // 归属交叉检查：gradient / mask_shape / blend_mode 只属于 clip 级（color 两级都合法）
                    if (FADE_ONLY_CLIP_FIELDS.contains(fieldName)) {
                        throw new ScriptParseException(p + "." + fieldName,
                                "只属于 clip 级（不能写在关键帧里，关键帧不生效）");
                    }
                    Object maskValue = parseFadeKeyframeField(fieldName, entry.getValue(), p);
                    if (maskValue != null) {
                        data.put(fieldName, maskValue);
                        continue;
                    }
                } else if (FADE_ONLY_KEYFRAME_FIELDS.contains(fieldName)
                        || FADE_ONLY_CLIP_FIELDS.contains(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "fade 专用字段（当前 layer_type=" + overlayLayerType
                                    + "）：高级颜色遮罩只属于 layer_type=fade");
                }
            }
            if (type == TrackType.CAMERA) {
                // 归属交叉检查（0.3.6 D2）：形状在 clip 级；几何严格有限 / 非负（不把非法值静默吞成缺省）。
                if ("mask_shape".equals(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "只属于 clip 级（形状在片段级声明，关键帧不生效）");
                }
                if (FADE_GRADIENT_FIELDS.contains(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "fade 专用（gradient / gradient_* 只属于 OVERLAY 的 layer_type=fade）："
                                    + "CAMERA lane 遮罩用 clip 级 mask_shape + 关键帧 mask_* 几何");
                }
                if ("blend_mode".equals(fieldName)) {
                    throw new ScriptParseException(p + "." + fieldName,
                            "只属于 clip 级（混合模式只写在 ADJUST 轨片段的 clip 级）");
                }
                if (CAMERA_MASK_GEOMETRY_FIELDS.contains(fieldName)) {
                    data.put(fieldName, parseCameraMaskKeyframeField(fieldName, entry.getValue(), p));
                    continue;
                }
            }
            Object value = parseFieldBySchema(fieldName, entry.getValue(), p, type, true, obj);
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

    /** OVERLAY 的生效 layer_type：缺省 / 非字符串按运行期口径回落 {@code fade}（见 OverlayTrackPlayer.createLayer）。 */
    private static String overlayLayerType(JsonObject clip) {
        return clip.has("layer_type") && clip.get("layer_type").isJsonPrimitive()
                ? clip.get("layer_type").getAsString() : "fade";
    }

    /**
     * 是否 fade 的「仅关键帧」字段（{@link #FADE_ONLY_KEYFRAME_FIELDS} 去掉两级都合法的 {@code color}）。
     * 复用同一集合做 clip 级交叉归属检查，避免再复制一套字段名。
     */
    private static boolean isFadeKeyframeOnlyField(String fieldName) {
        return !"color".equals(fieldName) && FADE_ONLY_KEYFRAME_FIELDS.contains(fieldName);
    }

    /**
     * 解析 fade 专用的 clip 级字段（0.3.6 D4–D7）。返回 {@code null} = 该字段不属于本方法。
     *
     * <p>这里只做 JSON 形态检查并归一为 data map 的值；渐变内容 / 枚举取值 / 颜色格式由
     * {@link ColorMaskSpec#from(Clip)} 在片段构建后统一校验（同一套口径，字段路径一致）。
     * 非 fade 的 OVERLAY 片段不会走到这里——调用方先按 layer_type 拒绝。</p>
     */
    private static Object parseFadeClipField(String fieldName, JsonElement value, String p)
            throws ScriptParseException {
        String path = p + "." + fieldName;
        return switch (fieldName) {
            case "color" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new ScriptParseException(path,
                            "需要字符串颜色（#RRGGBB 六位十六进制，# 可省）");
                }
                yield value.getAsString();
            }
            case "mask_shape" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new ScriptParseException(path,
                            "需要字符串（full / rect / circle；缺省 full）");
                }
                yield value.getAsString();
            }
            case "blend_mode" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new ScriptParseException(path,
                            "需要字符串（normal / multiply / screen / soft_light / overlay；缺省 normal）");
                }
                yield value.getAsString();
            }
            case "gradient" -> {
                if (!value.isJsonObject()) {
                    throw new ScriptParseException(path,
                            "需要对象 {type: linear|radial, stops: [...]}（缺省 = 纯色）");
                }
                // parseDataMap 会丢弃 JSON null（数组元素 / 对象值）——那会让 stops 下标错位、非法结构被吞。
                // 渐变结构里 null 没有合法语义，先整体拒绝，保证 ColorMaskSpec 看到的元素与作者写的逐项对应。
                if (containsJsonNull(value)) {
                    throw new ScriptParseException(path,
                            "不能包含 null（type / stops / 色标 offset / color 都必须有值）");
                }
                // 结构数据只解析一次：这里归一为 Map，ColorMaskSpec 再一次性校验并烘成数组
                yield parseDataMap(value.getAsJsonObject(), path);
            }
            default -> null;
        };
    }

    /**
     * 解析 fade 专用的关键帧字段（0.3.6 D4–D7）。返回 {@code null} = 该字段不属于本方法。
     *
     * <p>新增通道在 schema 里是 float / string；这里额外严格拦非数字 / 非有限值，
     * 避免 schema 驱动的「非数字 → null → 静默回落缺省」把非法输入悄悄变成缺省效果。
     * 取值范围（非负 / > 0 / 0~1）归 {@code ScriptValidator}（与既有通道分工一致）。</p>
     */
    private static Object parseFadeKeyframeField(String fieldName, JsonElement value, String p)
            throws ScriptParseException {
        String path = p + "." + fieldName;
        return switch (fieldName) {
            case "color" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new ScriptParseException(path,
                            "需要字符串颜色（#RRGGBB 六位十六进制，# 可省；缺省回落 clip.color）");
                }
                yield value.getAsString();
            }
            case "mask_x", "mask_y" -> requireFiniteNumber(value, path,
                    "需要有限数字（窗口比例，元素中心；缺省 0.5，可越界）");
            case "mask_width", "mask_height" -> requireFiniteNumber(value, path,
                    "需要有限数字（窗口比例，非负；缺省 1）");
            case "mask_radius" -> requireFiniteNumber(value, path,
                    "需要有限数字（窗口较短边比例，非负；缺省 0.5）");
            case "gradient_angle" -> requireFiniteNumber(value, path,
                    "需要有限数字（角度，0 = 向右、90 = 向下；缺省 0）");
            case "gradient_center_x", "gradient_center_y" -> requireFiniteNumber(value, path,
                    "需要有限数字（窗口比例；缺省 0.5，可越界）");
            case "gradient_radius" -> requireFiniteNumber(value, path,
                    "需要有限数字（窗口较短边比例；缺省 0.5，必须 > 0）");
            default -> null;
        };
    }

    /** 严格读取一个有限数字（新 fade 通道用；不把非数字 / NaN / Infinity 静默吞成缺省）。 */
    private static float requireFiniteNumber(JsonElement value, String path, String message)
            throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(path, message);
        }
        float number = value.getAsFloat();
        if (!Float.isFinite(number)) {
            throw new ScriptParseException(path, message + "（实际: " + value + "）");
        }
        return number;
    }

    /** 严格读取一个有限非负数字（CAMERA 遮罩尺寸 / 半径用；负数与 NaN / Infinity 都带字段路径拒绝）。 */
    private static float requireNonNegativeNumber(JsonElement value, String path, String message)
            throws ScriptParseException {
        float number = requireFiniteNumber(value, path, message);
        if (number < 0f) {
            throw new ScriptParseException(path, message + "（实际: " + number + "）");
        }
        return number;
    }

    /**
     * CAMERA lane 遮罩形状（clip 级 {@code mask_shape}）：{@code full} / {@code rect} / {@code circle}。
     * 非字符串 / 未知值都在解析期带字段路径拒绝（不静默回落 full）。
     */
    private static String parseMaskShape(JsonElement value, String path) throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(path, "需要字符串（full / rect / circle；缺省 full）");
        }
        String shape = value.getAsString();
        if (!"full".equals(shape) && !"rect".equals(shape) && !"circle".equals(shape)) {
            throw new ScriptParseException(path,
                    "未知覆盖形状: " + shape + "（可选: full / rect / circle）");
        }
        return shape;
    }

    /**
     * CAMERA lane 遮罩几何（关键帧 {@code mask_*}）：中心可越界（有限即可），
     * 宽高 / 半径有限非负。返回归一后的 {@code float} 存进关键帧 data。
     */
    private static float parseCameraMaskKeyframeField(String fieldName, JsonElement value, String p)
            throws ScriptParseException {
        String path = p + "." + fieldName;
        return switch (fieldName) {
            case "mask_x", "mask_y" -> requireFiniteNumber(value, path,
                    "需要有限数字（窗口比例，中心；缺省 0.5，可越界）");
            case "mask_width", "mask_height" -> requireNonNegativeNumber(value, path,
                    "需要有限非负数字（窗口比例；缺省 1，0 = 不绘制）");
            case "mask_radius" -> requireNonNegativeNumber(value, path,
                    "需要有限非负数字（窗口短边比例；缺省 0.5，0 = 不绘制）");
            default -> throw new ScriptParseException(path, "不是 CAMERA 遮罩几何字段");
        };
    }

    /** 递归检查 JSON 里是否含 null（parseDataMap / parseDataArray 会丢弃 null，渐变结构不允许）。 */
    private static boolean containsJsonNull(JsonElement value) {
        if (value.isJsonNull()) {
            return true;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (containsJsonNull(element)) return true;
            }
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (containsJsonNull(entry.getValue())) return true;
            }
        }
        return false;
    }

    /**
     * 根据 schema 字段类型解析一个 JSON 值
     */
    private static Object parseFieldBySchema(String fieldName, JsonElement value, String p,
                                              TrackType type, boolean isKeyframe, JsonObject kfObj)
            throws ScriptParseException {
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

        // 过渡打断规则（clip 级 transition_interrupt；schema 里是 enum，但额外拦非法枚举值）
        if (!isKeyframe && "transition_interrupt".equals(fieldName) && def != null) {
            return parseTransitionInterrupt(value, p + "." + fieldName);
        }

        // CAMERA lane 遮罩形状（clip 级 mask_shape）：schema 里是 enum，但额外拦非法枚举值
        // （OVERLAY fade 的同名字段在 parseFadeClipField 已按同一口径严格读取，不会走到这里）
        if (!isKeyframe && "mask_shape".equals(fieldName) && def != null && type == TrackType.CAMERA) {
            return parseMaskShape(value, p + "." + fieldName);
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
                yield parsePositionData(posObj, p + "." + fieldName, relative, kfObj);
            }
            case "bezier_curve" -> parseBezierCurve(value.getAsJsonObject(), p + "." + fieldName);
            case "color_curve" -> parseColorCurve(value, p + "." + fieldName);
            case "model" -> parseMathModel(value, p + "." + fieldName, fieldName);
            case "modifier" -> parseClipModifier(value, p + "." + fieldName);
            case "rules" -> parseClipRules(value, p + "." + fieldName);
            case "transition_params" -> parseTransitionParams(value, p + "." + fieldName);
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

    // ========== 数学模型定义解析（CAMERA clip 级 motion_* 字段）==========

    /**
     * 解析 {@code model} 字段 = 数学模型定义对象（JSON → 定义的唯一入口 = {@link MathModelRegistry#create}）。
     *
     * 形态：{@code {"type": "spring", "stiffness": 8.0}}；组合子定义内联（{@code chain} 的 {@code models}
     * 数组 / {@code blend} 的 {@code a}、{@code b}）。值域由字段名决定（{@code motion_position} = VECTOR、
     * {@code motion_yaw} / {@code motion_pitch} / {@code motion_roll} = ANGLE、{@code motion_fov} /
     * {@code motion_zoom} = SCALAR），以结构键 {@code domain} 交给注册表（缺省继承 = 通道值域）。
     *
     * 定义对象按归一形态（{@code type} + 参数键值 + 域）驻留：同形态的定义（含重新解析的同一脚本）
     * 得到同一对象，调用方据此判定「定义不变」；数字统一为 {@code float}，故 {@code 8} 与 {@code 8.0} 同形态。
     * 未注册 / 缺失 type、未知参数、越界值按注册表口径限频告警 + 回落（{@code linear} = 无效果），不阻断解析；
     * 值不是对象才视为结构错误。
     */
    private static MathModel parseMathModel(JsonElement value, String p, String fieldName) throws ScriptParseException {
        if (!value.isJsonObject()) {
            throw new ScriptParseException(p, "需要对象 {type, ...}（数学模型定义）");
        }
        Map<String, Object> raw = parseUnknownObject(value.getAsJsonObject(), p);
        raw.put("domain", MathModelRegistry.channelDomain(fieldName).key());
        return MODEL_DEFINITIONS.computeIfAbsent(raw, key -> {
            Object type = key.get("type");
            return MathModelRegistry.create(type instanceof String text ? text : null, key);
        });
    }

    // ========== 判定规则解析（clip 级 rules 字段）==========

    /**
     * 解析 clip 级 {@code rules}（判定规则数组 → {@link ClipRule} 列表）：逐元素 {@link ClipRule#parse}，
     * 元素结构非法即抛（走既有解析错误链，本片段不落地半成品规则表）。
     *
     * 空数组 = 空规则表（与不写等价；validator 记 issue 提示作者）。条件与动作原文原样保留——
     * 条件求值归判定源（C6-2）、动作执行归动作集（C6-3）。
     */
    private static List<ClipRule> parseClipRules(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonArray()) {
            throw new ScriptParseException(p, "需要数组（rules = 判定规则数组）");
        }
        JsonArray arr = value.getAsJsonArray();
        List<ClipRule> rules = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            rules.add(ClipRule.parse(arr.get(i), p + "[" + i + "]"));
        }
        return rules;
    }

    // ========== 覆盖链 Modifier 声明解析（CAMERA clip 级 modifier 字段）==========

    /**
     * 解析 clip 级 {@code modifier}（片段级静态 Modifier 声明 → {@link ClipModifier}）：{@code {channels, mode?,
     * priority?, <通道值>}}——结构键 {@code channels} / {@code mode} / {@code priority}，值键 = 通道名本身
     * （{@code position} 是 {@code {x, y, z}}，其余五个是有限数字）。字段语义见
     * {@code docs/SCRIPT_FORMAT.md} §「覆盖链 Modifier」；与 {@code ScriptValidator#checkModifier} 同一口径
     * （校验拦下的写法解析期也会拒绝）。
     */
    private static ClipModifier parseClipModifier(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonObject()) {
            throw new ScriptParseException(p, "需要对象 {channels, mode?, priority?, <通道值>}");
        }
        JsonObject obj = value.getAsJsonObject();
        int mask = parseModifierChannels(obj, p);
        ComposeMode mode = parseModifierMode(obj, p);
        int priority = parseModifierPriority(obj, p);
        double[] values = new double[ClipModifier.VALUE_COUNT];
        for (String name : CameraChannels.NAMES) {
            if ((mask & CameraChannels.bitOf(name)) == 0) {
                if (obj.has(name)) {
                    throw new ScriptParseException(p + "." + name,
                            "未在 channels 中声明（该通道不生效——要么加进 channels，要么删掉该键）");
                }
                continue;
            }
            parseModifierValue(obj, p, name, values);
        }
        rejectUnknownModifierKeys(obj, p);
        return new ClipModifier(priority, mask, mode, values);
    }

    /** 解析 {@code channels}（生效通道名数组）：非空、六通道名子集、不重复；返回掩码。 */
    private static int parseModifierChannels(JsonObject obj, String p) throws ScriptParseException {
        JsonElement e = obj.get("channels");
        if (e == null) {
            throw new ScriptParseException(p + ".channels",
                    "缺失（需要通道名数组，至少一个: " + CameraChannels.namesHint() + "）");
        }
        if (!e.isJsonArray()) {
            throw new ScriptParseException(p + ".channels",
                    "需要数组（通道名: " + CameraChannels.namesHint() + "）");
        }
        JsonArray channels = e.getAsJsonArray();
        if (channels.isEmpty()) {
            throw new ScriptParseException(p + ".channels",
                    "为空（至少声明一个生效通道: " + CameraChannels.namesHint() + "）");
        }
        int mask = 0;
        for (int i = 0; i < channels.size(); i++) {
            JsonElement channel = channels.get(i);
            String cp = p + ".channels[" + i + "]";
            if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isString()) {
                throw new ScriptParseException(cp, "需要字符串（通道名: " + CameraChannels.namesHint() + "）");
            }
            String name = channel.getAsString();
            int bit = CameraChannels.bitOf(name);
            if (bit == 0) {
                throw new ScriptParseException(cp,
                        "未知通道: " + name + "（可选: " + CameraChannels.namesHint() + "）");
            }
            if ((mask & bit) != 0) {
                throw new ScriptParseException(cp, "重复通道: " + name);
            }
            mask |= bit;
        }
        return mask;
    }

    /** 解析 {@code mode}（缺省 {@code null} = 逐通道缺省：偏移类 add、缩放类 multiply）。 */
    private static ComposeMode parseModifierMode(JsonObject obj, String p) throws ScriptParseException {
        if (!obj.has("mode")) return null;
        JsonElement e = obj.get("mode");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(p + ".mode",
                    "需要字符串（add / multiply / replace；缺省 = 逐通道缺省）");
        }
        String mode = e.getAsString();
        return switch (mode) {
            case "add" -> ComposeMode.ADD;
            case "multiply" -> ComposeMode.MULTIPLY;
            case "replace" -> ComposeMode.REPLACE;
            default -> throw new ScriptParseException(p + ".mode",
                    "未知合成模式: " + mode + "（可选: add / multiply / replace；缺省 = 逐通道缺省）");
        };
    }

    /** 解析 {@code priority}（应用序：升序，小者先应用；缺省 0）。 */
    private static int parseModifierPriority(JsonObject obj, String p) throws ScriptParseException {
        if (!obj.has("priority")) return 0;
        JsonElement e = obj.get("priority");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(p + ".priority", "需要整数（应用序：升序，小者先应用；缺省 0）");
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ScriptParseException(p + ".priority", "需要整数（应用序：升序，小者先应用；缺省 0）：" + value);
        }
        return (int) value;
    }

    /**
     * 解析一个已声明通道的值：{@code position} = 对象 {@code {x, y, z}}（世界坐标，方块）；
     * 其余五个 = 有限数字（REPLACE = 绝对值、ADD = 增量、MULTIPLY = 倍率）。
     */
    private static void parseModifierValue(JsonObject obj, String p, String name, double[] values)
            throws ScriptParseException {
        JsonElement e = obj.get(name);
        if (e == null) {
            throw new ScriptParseException(p + "." + name, "缺失（channels 已声明该通道，需要它的值）");
        }
        if (CameraChannels.POSITION == CameraChannels.bitOf(name)) {
            parseModifierPosition(e, p + ".position", values);
            return;
        }
        float scalar = parseModifierScalar(e, p + "." + name);
        // 通道名 → 值槽位（CameraChannels.NAMES 增删通道时必须同步本 switch）
        switch (name) {
            case "yaw" -> values[ClipModifier.SLOT_YAW] = scalar;
            case "pitch" -> values[ClipModifier.SLOT_PITCH] = scalar;
            case "roll" -> values[ClipModifier.SLOT_ROLL] = scalar;
            case "fov" -> values[ClipModifier.SLOT_FOV] = scalar;
            case "zoom" -> values[ClipModifier.SLOT_ZOOM] = scalar;
        }
    }

    /** 解析 {@code position} 通道值（整通道一位）：{@code x} / {@code y} / {@code z} 三个都要写，且都是有限数字。 */
    private static void parseModifierPosition(JsonElement e, String p, double[] values) throws ScriptParseException {
        if (!e.isJsonObject()) {
            throw new ScriptParseException(p, "需要对象 {x, y, z}（position 是整通道一位，三轴都要写）");
        }
        JsonObject position = e.getAsJsonObject();
        for (String axis : position.keySet()) {
            if (!"x".equals(axis) && !"y".equals(axis) && !"z".equals(axis)) {
                throw new ScriptParseException(p + "." + axis, "未知键（position 只认 x / y / z）");
            }
        }
        values[ClipModifier.SLOT_POSITION_X] = requireModifierAxis(position, p, "x");
        values[ClipModifier.SLOT_POSITION_Y] = requireModifierAxis(position, p, "y");
        values[ClipModifier.SLOT_POSITION_Z] = requireModifierAxis(position, p, "z");
    }

    /** 一个位置分量：存在且为有限数字（世界坐标，方块）。 */
    private static double requireModifierAxis(JsonObject position, String p, String axis) throws ScriptParseException {
        JsonElement e = position.get(axis);
        if (e == null) {
            throw new ScriptParseException(p + "." + axis, "缺失（position 的三轴 x / y / z 都要写）");
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(p + "." + axis, "需要数字（世界坐标，方块）");
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new ScriptParseException(p + "." + axis, "不是有限数字（NaN / Infinity）");
        }
        return value;
    }

    /** 一个标量通道值：存在且为有限数字。 */
    private static float parseModifierScalar(JsonElement e, String p) throws ScriptParseException {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(p, "需要数字（REPLACE = 绝对值、ADD = 增量、MULTIPLY = 倍率）");
        }
        float value = e.getAsFloat();
        if (!Float.isFinite(value)) {
            throw new ScriptParseException(p, "不是有限数字（NaN / Infinity）");
        }
        return value;
    }

    /** 结构键之外只允许通道名（值键）：其余键一律拒绝（写错的键静默无效 = 脚本没错但画面不对）。 */
    private static void rejectUnknownModifierKeys(JsonObject obj, String p) throws ScriptParseException {
        for (String key : obj.keySet()) {
            if ("channels".equals(key) || "mode".equals(key) || "priority".equals(key)) continue;
            if (CameraChannels.bitOf(key) != 0) continue;
            throw new ScriptParseException(p + "." + key, "未知键（结构键: channels / mode / priority；值键: 通道名 "
                    + CameraChannels.namesHint() + "）");
        }
    }

    // ========== 过渡声明解析 ==========

    /**
     * 解析 clip 级 {@code transition_params}（过渡分参数声明 → {@link TransitionParams}）：
     * {@code {<通道>: {mode?, duration?, curve?}}}——通道名见 {@link CameraChannels}；未声明的通道 / 键保留
     * 「未声明」标记（由 {@code Clip.getTransitionSpec()} 按片段级缺省补齐）。字段语义见
     * {@code docs/SCRIPT_FORMAT.md} §「过渡（transition）」；与
     * {@code ScriptValidator#checkTransition} 同一口径（校验拦下的写法解析期也会拒绝）。
     */
    private static TransitionParams parseTransitionParams(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonObject()) {
            throw new ScriptParseException(p, "需要对象 {<通道>: {mode?, duration?, curve?}}（通道名: "
                    + CameraChannels.namesHint() + "）");
        }
        TransitionMode[] modes = new TransitionMode[TransitionParams.CHANNELS];
        float[] durations = new float[TransitionParams.CHANNELS];
        MathModel[] curves = new MathModel[TransitionParams.CHANNELS];
        for (int i = 0; i < TransitionParams.CHANNELS; i++) {
            durations[i] = Float.NaN;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String channel = entry.getKey();
            int index = Integer.numberOfTrailingZeros(CameraChannels.bitOf(channel));
            if (index < 0 || index >= TransitionParams.CHANNELS) {
                throw new ScriptParseException(p + "." + channel,
                        "未知通道（可用: " + CameraChannels.namesHint() + "）");
            }
            if (!entry.getValue().isJsonObject()) {
                throw new ScriptParseException(p + "." + channel, "需要对象 {mode?, duration?, curve?}");
            }
            for (Map.Entry<String, JsonElement> key : entry.getValue().getAsJsonObject().entrySet()) {
                String channelPath = p + "." + channel;
                switch (key.getKey()) {
                    case "mode" -> modes[index] = parseTransitionMode(key.getValue(), channelPath + ".mode");
                    case "duration" -> durations[index] =
                            parseTransitionDuration(key.getValue(), channelPath + ".duration");
                    case "curve" -> curves[index] =
                            parseMathModel(key.getValue(), channelPath + ".curve", "transition_curve");
                    default -> throw new ScriptParseException(channelPath + "." + key.getKey(),
                            "未知键（结构键: mode / duration / curve）");
                }
            }
        }
        return new TransitionParams(modes, durations, curves);
    }

    /** 过渡打断规则：脚本用名 {@code blend} / {@code snap}。 */
    private static String parseTransitionInterrupt(JsonElement value, String p) throws ScriptParseException {
        String name = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
        if (TransitionInterrupt.byName(name) == null) {
            throw new ScriptParseException(p, "需要 blend / snap（缺省 blend）");
        }
        return name;
    }

    /** 过渡通道模式：脚本用名 {@code blend} / {@code hold} / {@code cut}。 */
    private static TransitionMode parseTransitionMode(JsonElement value, String p) throws ScriptParseException {
        String name = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
        TransitionMode mode = TransitionMode.byName(name);
        if (mode == null) {
            throw new ScriptParseException(p, "需要 blend / hold / cut（实际: " + value + "）");
        }
        return mode;
    }

    /** 过渡通道时长：有限正数（秒）——不做过渡请用 {@code mode: "cut"}。 */
    private static float parseTransitionDuration(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(p, "需要数字（秒，> 0）");
        }
        float duration = value.getAsFloat();
        if (!Float.isFinite(duration) || duration <= 0f) {
            throw new ScriptParseException(p, "需要有限正数（秒）；不做过渡请用 mode: \"cut\"");
        }
        return duration;
    }

    // ========== PositionData 解析 ==========

    /**
     * 位置通道解析：绝对模式读世界坐标 {@code x/y/z}；相对模式读偏移 + 位置点源 + 基准坐标系前轴目标。
     * 偏移两表达空间二选一：世界轴 {@code dx/dy/dz}；基准坐标系 {@code fwd/up/right}。
     */
    private static PositionData parsePositionData(JsonObject obj, String p, boolean positionModeRelative,
                                                 JsonObject kfObj) throws ScriptParseException {
        if (!positionModeRelative) {
            if (!obj.has("x")) {
                throw new ScriptParseException(p, "absolute 模式需要 x/y/z 字段");
            }
            return PositionData.absolute(requireFloat(obj, p, "x"), requireFloat(obj, p, "y"),
                    requireFloat(obj, p, "z"));
        }
        rejectRemovedOrigin(obj, p);
        if (obj.has("up_axis")) {
            throw new ScriptParseException(p + ".up_axis", REMOVED_UP_AXIS);
        }
        boolean baseSpace = obj.has("fwd") || obj.has("up") || obj.has("right");
        if (baseSpace && (obj.has("dx") || obj.has("dy") || obj.has("dz"))) {
            throw new ScriptParseException(p,
                    "fwd/up/right（基准坐标系偏移）不能与 dx/dy/dz（世界轴偏移）混用");
        }
        if (baseSpace) {
            return parsePositionOrigin(kfObj, p, true, optFloat(obj, "fwd", 0f), optFloat(obj, "up", 0f),
                    optFloat(obj, "right", 0f));
        }
        if (!obj.has("dx")) {
            throw new ScriptParseException(p,
                    "relative 模式需要 dx/dy/dz（世界轴偏移）或 fwd/up/right（基准坐标系偏移）字段");
        }
        return parsePositionOrigin(kfObj, p, false, requireFloat(obj, p, "dx"), requireFloat(obj, p, "dy"),
                requireFloat(obj, p, "dz"));
    }

    /**
     * 位置点源的移除字段：0.3.6 起位置基准统一用关键帧字段 {@code facing_origin}（五形态），
     * {@code position.relative_origin} 与配套坐标不再存在；{@code facing_origin} / {@code facing_target}
     * 写在关键帧上（与 {@code yaw_base_from} 同级），不写在 position 内。
     */
    private static void rejectRemovedOrigin(JsonObject posObj, String p) throws ScriptParseException {
        for (String field : new String[]{"relative_origin", "relative_origin_x", "relative_origin_y",
                "relative_origin_z"}) {
            if (posObj.has(field)) {
                throw new ScriptParseException(p + "." + field,
                        "已移除（0.3.6 起）：位置基准统一用关键帧字段 facing_origin"
                                + "（写法见 docs/SCRIPT_FORMAT.md §「点源」）");
            }
        }
        if (posObj.has("facing_origin") || posObj.has("facing_target")) {
            throw new ScriptParseException(p + ".facing_origin",
                    "facing_origin / facing_target 是关键帧字段（与 yaw_base_from 同级），不写在 position 内");
        }
    }

    /**
     * 位置点源 + 偏移装配。位置点源 = 关键帧字段 {@code facing_origin}（五形态：{@code "player"} 玩家激活位置 /
     * {@code "coordinate"} + {@code facing_origin_x/y/z} / {@code block:id[:radius]} / 实体选择器 / 结构 id）；
     * 不写 = 玩家激活位置（世界轴偏移）/ 玩家实体 {@code "@p"}（基准坐标系偏移，实时跟随）。
     * 偏移按 {@code baseSpace} 装配：true = 基准坐标系（fwd/up/right），false = 世界轴（dx/dy/dz）。
     * 前轴目标 = 关键帧字段 {@code facing_target}（点源形态字符串，空 = 位置点源自身朝向）。
     */
    private static PositionData parsePositionOrigin(JsonObject kfObj, String p, boolean baseSpace,
                                                    float a, float b, float c)
            throws ScriptParseException {
        JsonElement originEl = kfObj.get("facing_origin");
        if (originEl != null && (!originEl.isJsonPrimitive() || !originEl.getAsJsonPrimitive().isString())) {
            throw new ScriptParseException(p + ".facing_origin", "facing_origin 只支持字符串写法");
        }
        String origin = originEl != null ? originEl.getAsString() : "";
        String facingTarget = optString(kfObj, "facing_target", "");
        PositionData pd = switch (PositionData.originKindOf(origin)) {
            case PositionData.ORIGIN_COORDINATE -> {
                float ox = requireFloat(kfObj, p, "facing_origin_x");
                float oy = requireFloat(kfObj, p, "facing_origin_y");
                float oz = requireFloat(kfObj, p, "facing_origin_z");
                yield baseSpace ? PositionData.facingToCoordinate(a, b, c, ox, oy, oz)
                        : PositionData.relativeToCoordinate(a, b, c, ox, oy, oz);
            }
            case PositionData.ORIGIN_BLOCK -> {
                String[] parsed = PositionData.parseBlockOriginString(origin);
                int radius = Integer.parseInt(parsed[1]);
                yield baseSpace ? PositionData.facingToBlock(a, b, c, parsed[0], radius)
                        : PositionData.relativeToBlock(a, b, c, parsed[0], radius);
            }
            case PositionData.ORIGIN_STRUCTURE -> baseSpace
                    ? PositionData.facingToStructure(a, b, c, origin)
                    : PositionData.relativeToStructure(a, b, c, origin);
            case PositionData.ORIGIN_SELECTOR -> baseSpace
                    ? PositionData.facingToEntity(origin, a, b, c)
                    : PositionData.relativeToEntity(a, b, c, origin);
            case PositionData.ORIGIN_PLAYER -> baseSpace
                    ? PositionData.facingToPlayer(a, b, c)
                    : PositionData.relative(a, b, c);
            default -> baseSpace
                    ? PositionData.facingToEntity("@p", a, b, c)
                    : PositionData.relative(a, b, c);
        };
        return pd.withFacingTarget(facingTarget);
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
        // 触发器级轮询间隔（可选，tick）：缺省/非法 → null，运行时回落到脚本级 → 类型级默认
        Integer pollInterval = optPollInterval(obj, "poll_interval", p);
        return new TriggerDefinition(type, conditions, repeatable, delay, onEnter, exitBuffer, requires, pollInterval);
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
     * 可选轮询间隔（tick，{@link TriggerType#POLL_INTERVAL_MIN}~{@link TriggerType#POLL_INTERVAL_MAX}）：
     * 缺省 / JSON null → null（回落到下一级）；非整数 / 越界 → 告警 + null（防御式，与 skip_vote_ratio 同口径）。
     *
     * @param key  字段名（取值）
     * @param path 报错用路径（如 {@code meta.triggers[0]}）
     */
    private static Integer optPollInterval(JsonObject obj, String key, String path) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        String fp = path + "." + key;
        double d = el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsDouble() : Double.NaN;
        if (!Double.isFinite(d) || d != Math.floor(d)) {
            ErrorLog.log("Parse", fp + " 需要整数（tick，" + TriggerType.POLL_INTERVAL_MIN + "~"
                    + TriggerType.POLL_INTERVAL_MAX + "），实际: " + el + "，已忽略（回落到下一级默认）");
            return null;
        }
        int v = (int) d;
        if (v < TriggerType.POLL_INTERVAL_MIN || v > TriggerType.POLL_INTERVAL_MAX) {
            ErrorLog.log("Parse", fp + " 超出范围 " + TriggerType.POLL_INTERVAL_MIN + "~"
                    + TriggerType.POLL_INTERVAL_MAX + "（tick），实际: " + v + "，已忽略（回落到下一级默认）");
            return null;
        }
        return v;
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
