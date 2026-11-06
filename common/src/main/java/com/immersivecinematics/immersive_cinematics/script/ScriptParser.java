package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ComposeMode;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionMode;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionParams;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitKind;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitPolicy;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipIdentity;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipRef;
import com.immersivecinematics.immersive_cinematics.script.identity.UsageIndex;
import com.immersivecinematics.immersive_cinematics.script.identity.UsageKind;
import com.immersivecinematics.immersive_cinematics.script.object.ClipObjects;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectFormatException;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectParser;
import com.immersivecinematics.immersive_cinematics.script.object.SampledProperty;
import com.immersivecinematics.immersive_cinematics.script.path.PathCollectionParser;
import com.immersivecinematics.immersive_cinematics.script.rule.ClipExitPolicyParser;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleCollectionParser;
import com.immersivecinematics.immersive_cinematics.script.schema.MathModelRegistry;
import com.immersivecinematics.immersive_cinematics.script.track.TrackRegistry;
import com.immersivecinematics.immersive_cinematics.spi.FieldDef;
import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.MathModel;
import com.immersivecinematics.immersive_cinematics.spi.PropertyMode;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.spi.TriggerType;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本解析器 — 将 JSON 字符串解析为 {@link CinematicScript}。
 *
 * 使用 Gson 的 JsonElement 树 API 手动解析（不做反射绑定）；字段表由 {@link SchemaLoader} 驱动。
 * 报错即停：首个错误抛 {@link ScriptParseException}，消息带字段路径（如 {@code meta.id}）。
 */
public class ScriptParser {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptParser");

    /**
     * 模型定义驻留表：键 = 定义对象的归一形态（{@code type} + 参数键值 + 通道域，见 {@link #parseMathModel}）。
     *
     * 同（type + 参数键值）的 JSON 定义复用同一定义对象，于是「定义对象同一」= 「定义不变」——
     * 调用方（{@code camera/source/LaneMotionStepper}）据此判定片段切换时模型实例延续 / 重建；
     * 定义不可变、可被多个调用点共享（实例仍按调用点各建一份）。
     * 键为解析期一次建成的只读 map（入库后不再变更）；条目数 = 出现过的定义形态数。
     */
    private static final Map<Map<String, Object>, MathModel> MODEL_DEFINITIONS = new ConcurrentHashMap<>();

    /** 解析异常 — 消息 = {@code 字段路径: 说明}；{@link #getFieldPath()} 返回路径部分。 */
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

    /**
     * 解析入口：{@link ScriptParseException} 原样上抛；其余 RuntimeException（典型为字段类型错误：
     * 数字字段写成字符串 → NumberFormatException，对象/数组字段写成标量 → UnsupportedOperationException）
     * 统一收敛为带根路径的解析异常，调用方只需处理一种异常。
     *
     * <p>首行冻结轨道注册表（进程内一次性）：此后第三方注册必须抛 {@code IllegalStateException}，
     * 保证注册窗口 = 模组初始化期。</p>
     */
    public static CinematicScript parse(String json) throws ScriptParseException {
        TrackRegistry.freeze();
        try {
            return parseInternal(json);
        } catch (RuntimeException e) {
            throw new ScriptParseException("<root>", "字段类型错误: " + e.getMessage(), e);
        }
    }

    private static CinematicScript parseInternal(String json) throws ScriptParseException {
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
        boolean hasScriptRules = rootObj.has("rules") || rootObj.has("rule_bindings");
        ScriptMeta meta = parseMeta(rootObj);
        Timeline timeline = parseTimeline(rootObj, "timeline");
        List<Clip> standalone = parseStandaloneClips(rootObj);
        CinematicScript script = new CinematicScript(meta, timeline, standalone);
        // 保留原始 JSON：meta.listener 等运行期扩展字段只在 rawJson 里读取；
        // 服务端推送脚本也必须走这里，否则脚本会被当成默认 listener=player。
        script.setRawJson(json);
        script.setUsageBuilder(buildUsageIndex(timeline));
        // 片段默认出口（exit）：脚本对象就绪后才解析 goto 目标；使用边登记进同一构建器。
        resolveClipExits(timeline, standalone, script);
        // 脚本级规则集合（rules / rule_bindings）：格式级的互斥检查已在前，这里只做解析与登记。
        // 放在 setUsageBuilder 之后：绑定 / 分支 / 出口的使用边直接登记进同一构建器。
        if (hasScriptRules) {
            script.setRuleCollection(
                    RuleCollectionParser.parse(rootObj, script::findClip, script.usageBuilder(), "<root>"));
        }
        // 脚本级路径集合（paths[]）：与规则集合同一装配顺序（usage builder 就绪后登记使用边）。
        script.setPathCollection(
                PathCollectionParser.parse(rootObj, script::findClip, script.usageBuilder(), "<root>"));
        return script;
    }

    /**
     * 编译轨道放置边：每条内联片段登记一条 {@code TRACK_PLACEMENT}，
     * 宿主路径 = {@code timeline.tracks[i].clips[j]}（与校验器 / 报错文案同一套定位串）。
     */
    private static UsageIndex.Builder buildUsageIndex(Timeline timeline) {
        UsageIndex.Builder builder = UsageIndex.builder();
        List<TimelineTrack> tracks = timeline.getTracks();
        for (int ti = 0; ti < tracks.size(); ti++) {
            List<Clip> clips = tracks.get(ti).getClips();
            for (int ci = 0; ci < clips.size(); ci++) {
                builder.placement(clips.get(ci), "timeline.tracks[" + ti + "].clips[" + ci + "]");
            }
        }
        return builder;
    }

    /**
     * 解析全部片段的默认出口（内联 + 独立片段）。必须在 {@link CinematicScript} 建好且
     * {@code usageBuilder} 已装入之后调用——{@code goto} 目标按片段 id 解析，脚本对象是唯一解析器。
     *
     * <p>未声明 {@code exit} 的片段保持 {@code null} 策略（运行期 = {@code CONTINUE}）。GOTO 目标登记
     * 一条 {@link UsageKind#EXIT_TARGET} 使用边（宿主 = 片段 JSON 路径 + {@code .exit}），
     * 于是「被引用必填 id」由同一索引覆盖。</p>
     */
    private static void resolveClipExits(Timeline timeline, List<Clip> standalone, CinematicScript script)
            throws ScriptParseException {
        List<TimelineTrack> tracks = timeline.getTracks();
        for (int ti = 0; ti < tracks.size(); ti++) {
            List<Clip> clips = tracks.get(ti).getClips();
            for (int ci = 0; ci < clips.size(); ci++) {
                applyClipExit(clips.get(ci),
                        "timeline.tracks[" + ti + "].clips[" + ci + "].exit", script);
            }
        }
        for (int i = 0; i < standalone.size(); i++) {
            applyClipExit(standalone.get(i), "clips[" + i + "].exit", script);
        }
    }

    private static void applyClipExit(Clip clip, String path, CinematicScript script)
            throws ScriptParseException {
        JsonObject rawExit = clip.getRawExit();
        if (rawExit == null) return;
        ClipExitPolicy policy = ClipExitPolicyParser.parse(rawExit, script::findClip, path);
        clip.setExitPolicy(policy);
        UsageIndex.Builder usage = script.usageBuilder();
        if (usage != null && policy.kind() == ClipExitKind.GOTO) {
            usage.reference(UsageKind.EXIT_TARGET, path,
                    ClipRef.of(policy.target(), policy.entryLocalTime()));
        }
    }

    // ========== ScriptMeta 解析 ==========

    private static ScriptMeta parseMeta(JsonObject root) throws ScriptParseException {
        String p = "meta";
        JsonObject metaObj = requireObject(root, p, "meta");

        // 旧字段：0.3.6 起已移除，出现即报错（不保留双读 / 兼容分支）
        if (metaObj.has("version")) {
            throw new ScriptParseException(p + ".version",
                    "已移除：meta.version（0.3.6 起不再有格式版本号）");
        }
        if (metaObj.has("hud_layers")) {
            throw new ScriptParseException(p + ".hud_layers",
                    "已移除：meta.hud_layers；用 meta.screen_layers（true = 显示）");
        }
        if (metaObj.has("hard_hide_hud")) {
            throw new ScriptParseException(p + ".hard_hide_hud",
                    "已移除：meta.hard_hide_hud；用 meta.screen_layer_mode（allowlist / normal）");
        }

        String id = requireString(metaObj, p, "id");
        String name = requireString(metaObj, p, "name");
        String author = requireString(metaObj, p, "author");
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
        // 画面层（screen_layers）：值 = 显示（true 显示 / false 隐藏）
        java.util.Map<String, Boolean> screenLayers = parseScreenLayers(metaObj);
        // 模式归一：显式 screen_layer_mode > screen_layers 字段存在 → allowlist > normal
        ScreenLayerMode screenLayerMode = resolveScreenLayerMode(metaObj);
        // 编辑基准分辨率（可选）：{w, h} 正整数；缺省 1920×1080，非法 → 告警 + 回落缺省（防御式）
        int[] baseResolution = parseBaseResolution(metaObj);

        ScriptMeta.RuntimeBehavior behavior = new ScriptMeta.RuntimeBehavior(
                blockKeyboard, blockMouse, blockMobAi,
                hideHud, hideArm, suppressBob, suppressDistortion,
                hideChat, hideScoreboard, hideActionBar,
                hideTitle, hideSubtitles, hideHotbar, hideCrosshair,
                hideBossbar, hideSkipHud,
                screenLayerMode,
                renderPlayerModel,
                pauseWhenGamePaused, interruptible, skippable,
                holdAtEnd, macroLoop, macroLoopMode, macroLoopCount, screenLayers);

        // 播放优先级（默认值来自 SchemaRegistry.getMetaFields()；仅用于队列内排序）
        int priority = optInt(metaObj, "priority", 0);

        // 跳过投票比例（可选，整数 10~100）：缺省 / JSON null → null（运行时回落到全局配置 Config.skipVoteRatio）
        // 非整数或越界 → 解析期报错（不允许 0）
        Integer skipVoteRatio = null;
        if (metaObj.has("skip_vote_ratio") && !metaObj.get("skip_vote_ratio").isJsonNull()) {
            JsonElement ratioEl = metaObj.get("skip_vote_ratio");
            if (!ratioEl.isJsonPrimitive() || !ratioEl.getAsJsonPrimitive().isNumber()) {
                throw new ScriptParseException("meta.skip_vote_ratio",
                        "必须为整数（百分比 10~100），实际: " + ratioEl);
            }
            double ratioValue = ratioEl.getAsDouble();
            if (!Double.isFinite(ratioValue) || ratioValue != Math.floor(ratioValue)) {
                throw new ScriptParseException("meta.skip_vote_ratio",
                        "必须为整数（百分比 10~100），实际: " + ratioEl);
            }
            int ratioInt = (int) ratioValue;
            if (ratioInt < 10 || ratioInt > 100) {
                throw new ScriptParseException("meta.skip_vote_ratio",
                        "取值范围 10~100（不允许 0），实际: " + ratioInt);
            }
            skipVoteRatio = ratioInt;
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
                triggers.add(parseTriggerDefinition(trigArr.get(i).getAsJsonObject(), p + ".triggers[" + i + "]", i));
            }
        }

        return new ScriptMeta(id, name, author, description, behavior, priority, playMode, dimension, triggers, skipVoteRatio,
                triggerPollInterval, baseResolution[0], baseResolution[1]);
    }

    // ========== Timeline 解析 ==========

    private static Timeline parseTimeline(JsonObject root, String key) throws ScriptParseException {
        String p = key;
        if (!root.has(key)) {
            throw new ScriptParseException(p, "缺少必填字段: " + key);
        }
        JsonObject timelineObj = requireObject(root, p, key);
        // 旧字段：0.3.6 起已移除（有限末端 / 显示范围由片段窗口派生）
        if (timelineObj.has("total_duration")) {
            throw new ScriptParseException(p + ".total_duration",
                    "已移除：timeline.total_duration；有限末端/显示范围由片段窗口派生");
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
        return new Timeline(tracks, loopStart, loopEnd);
    }

    // ========== Track 解析（统一 schema 驱动）==========

    private static TimelineTrack parseTrack(JsonObject trackObj, String p) throws ScriptParseException {
        String typeStr = requireString(trackObj, p, "type");
        TrackTypeId type = parseTrackType(typeStr, p + ".type");
        JsonArray clipsArr = requireArray(trackObj, p, "clips");

        List<Clip> clips = new ArrayList<>();
        for (int i = 0; i < clipsArr.size(); i++) {
            clips.add(parseClip(clipsArr.get(i).getAsJsonObject(), p + ".clips[" + i + "]", type));
        }

        return new TimelineTrack(type, clips);
    }

    // ========== Clip 解析（统一 schema 驱动）==========

    private static Clip parseClip(JsonObject obj, String p, TrackTypeId type) throws ScriptParseException {
        // 旧字段：0.3.6 起已移除，出现即报错（不保留双读 / 兼容分支）
        rejectRemovedClipFields(obj, p);
        return parseObjectClip(obj, p, type);
    }

    /** 旧片段字段：0.3.6 起已移除，出现即报错（文案统一含「已移除」）。 */
    private static void rejectRemovedClipFields(JsonObject obj, String p) throws ScriptParseException {
        for (String field : obj.keySet()) {
            String message = removedClipFieldMessage(field);
            if (message != null) {
                throw new ScriptParseException(p + "." + field, message);
            }
        }
    }

    /** 旧片段字段的「已移除」文案；{@code null} = 不是旧字段。 */
    private static String removedClipFieldMessage(String field) {
        switch (field) {
            case "keyframes":
                return "已移除：keyframes；用 objects（每个可调项一个采样属性）";
            case "curve":
                return "已移除：clip 级 curve；用脚本级 paths（每条引用目标片段与两个相邻位置关键帧）";
            case "rules":
                return "已移除：clip 级 rules；用脚本级 rules + rule_bindings";
            case "interpolation":
                return "已移除：clip.interpolation；缓动由编辑器烘焙成关键帧";
            case "follow":
            case "follow_selector":
                return "已移除：" + field + "；用 objects 的 position_origin / position_origin_freeze";
            case "orient":
            case "yaw_offset":
            case "pitch_offset":
                return "已移除：clip 级 " + field + "；写进 objects";
            default:
                break;
        }
        if (field.startsWith("facing_origin")) {
            return "已移除：" + field + "；用 objects 的 position_origin / position_origin_freeze";
        }
        if (field.startsWith("selector_")
                && (field.endsWith("_follow") || field.endsWith("_facing_origin"))) {
            return "已移除：" + field + "；用 selector_*_position_origin";
        }
        return null;
    }

    /**
     * 片段解析的唯一入口：{@code start_time} / {@code duration} + 播放控制 + {@code objects}。
     *
     * <p>旧格式字段在 {@link #parseClip} 处已按「已移除」拒绝；其余未知键在 {@link ObjectParser}
     * 的片段级白名单处被拒绝。</p>
     */
    private static Clip parseObjectClip(JsonObject obj, String p, TrackTypeId type) throws ScriptParseException {
        return parseObjectClip(obj, p, type, requireFloat(obj, p, "start_time"));
    }

    /**
     * 片段解析（显式起始秒）：独立片段没有默认放置，调用方传 {@code 0} 且不写回 JSON；
     * 内联片段传自己的 {@code start_time}。
     *
     * @param startTime 片段起始秒（不再从 {@code obj} 读取）
     */
    private static Clip parseObjectClip(JsonObject obj, String p, TrackTypeId type, float startTime)
            throws ScriptParseException {
        float duration = requireFloat(obj, p, "duration");
        String clipId = parseClipId(obj, p);
        // 与旧口径一致：0 非法；负数 = 无限时长
        if (duration == 0f) {
            throw new ScriptParseException(p + ".duration", "不允许为0，正数=有限时长，负数=无限时长，实际: " + duration);
        }

        ClipObjects objects;
        try {
            objects = ObjectParser.parse(obj, p, type);
        } catch (ObjectFormatException e) {
            throw new ScriptParseException(e.fieldPath(), detailOf(e));
        }
        // STRUCT 值由 ObjectCodec 落成原始 Map/List；强类型消费方（Clip#getModifier 等）要求解析期归一
        objects = normalizeStructs(objects, obj.getAsJsonObject("objects"), p, type);

        Map<String, Object> data = new HashMap<>();
        if (obj.has("loop")) {
            JsonElement loop = obj.get("loop");
            if (!loop.isJsonPrimitive() || !loop.getAsJsonPrimitive().isBoolean()) {
                throw new ScriptParseException(p + ".loop", "需要布尔值");
            }
            data.put("loop", loop.getAsBoolean());
        }
        if (obj.has("loop_count")) {
            data.put("loop_count", requireLoopCount(obj.get("loop_count"), p + ".loop_count"));
        }
        if (obj.has("loop_mode")) {
            JsonElement loopMode = obj.get("loop_mode");
            if (!loopMode.isJsonPrimitive() || !loopMode.getAsJsonPrimitive().isString()) {
                throw new ScriptParseException(p + ".loop_mode", "需要字符串（repeat / pingpong）");
            }
            String mode = loopMode.getAsString();
            if (!"repeat".equals(mode) && !"pingpong".equals(mode)) {
                throw new ScriptParseException(p + ".loop_mode", "需要 repeat / pingpong，实际: " + mode);
            }
            data.put("loop_mode", mode);
        }
        // loop_count=0 非法：与旧分支同一口径（记录错误并按 1 处理，不阻断运行）
        Object loopCount = data.get("loop_count");
        if (loopCount instanceof Number && ((Number) loopCount).intValue() == 0) {
            ErrorLog.log("Parse", p + ".loop_count 不允许为 0（-1=无限循环，正整数=循环次数），已按 1 处理");
            data.put("loop_count", 1);
        }

        Clip clip = new Clip(startTime, duration, type, data, objects, clipId, null);
        // 出口声明延后解析：此刻脚本对象不存在，goto 目标无法解析。这里只做结构形态检查并暂存原文，
        // 由 parseInternal 在脚本装配完成后统一解析（内联 / 独立片段同一口径）。
        if (obj.has("exit")) {
            JsonElement exitElement = obj.get("exit");
            if (!exitElement.isJsonObject()) {
                throw new ScriptParseException(p + ".exit", "需要对象（出口策略）");
            }
            clip.setRawExit(exitElement.getAsJsonObject());
        }
        return clip;
    }

    /**
     * 片段 {@code id}（可选）：存在则必须是非空字符串并匹配 {@link ClipIdentity#PATTERN}。
     * 内联片段与独立片段走同一口径；重复 / 悬空引用由 {@code ScriptValidator} 报错，
     * 解析期只保证格式合法（避免旧解析基线漂移）。
     */
    private static String parseClipId(JsonObject obj, String p) throws ScriptParseException {
        if (!obj.has("id")) return null;
        JsonElement element = obj.get("id");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(p + ".id", "需要字符串（片段 id，匹配 " + ClipIdentity.PATTERN + "）");
        }
        String id = element.getAsString();
        if (id.isEmpty()) {
            throw new ScriptParseException(p + ".id", "不能为空字符串（片段 id 需匹配 " + ClipIdentity.PATTERN + "）");
        }
        if (!ClipIdentity.isValid(id)) {
            throw new ScriptParseException(p + ".id",
                    "不合法: " + id + "（需匹配 " + ClipIdentity.PATTERN + "）");
        }
        return id;
    }

    /**
     * 解析脚本级独立片段表 {@code clips[]}（缺省 = 无独立片段）。
     *
     * <p>条目 = 没有默认放置的新格式片段：{@code track} 必填（决定 {@code objects} schema）、
     * {@code duration} 必填非 0、禁止 {@code start_time}、{@code objects} 必须是新格式对象。
     * {@code track} 只属于本表，交给 {@link #parseObjectClip} 前从副本里摘掉——片段本体不保留它。</p>
     */
    private static List<Clip> parseStandaloneClips(JsonObject root) throws ScriptParseException {
        String p = "clips";
        if (!root.has("clips")) {
            return List.of();
        }
        JsonElement element = root.get("clips");
        if (!element.isJsonArray()) {
            throw new ScriptParseException(p, "需要数组（脚本级独立片段表；缺省 = 无独立片段）");
        }
        JsonArray array = element.getAsJsonArray();
        List<Clip> clips = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String cp = p + "[" + i + "]";
            JsonElement entry = array.get(i);
            if (!entry.isJsonObject()) {
                throw new ScriptParseException(cp, "需要对象（独立片段）");
            }
            JsonObject obj = entry.getAsJsonObject();
            if (obj.has("start_time")) {
                throw new ScriptParseException(cp + ".start_time",
                        "独立片段不能有 start_time（没有默认放置；需要放置请写 timeline.tracks[].clips[] 的内联片段）");
            }
            if (!obj.has("track")) {
                throw new ScriptParseException(cp + ".track", "缺少必填字段（独立片段需要轨道类型）");
            }
            JsonElement trackElement = obj.get("track");
            if (!trackElement.isJsonPrimitive() || !trackElement.getAsJsonPrimitive().isString()) {
                throw new ScriptParseException(cp + ".track", "需要字符串（轨道类型名）");
            }
            TrackTypeId type = parseTrackType(trackElement.getAsString(), cp + ".track");
            if (!obj.has("objects")) {
                throw new ScriptParseException(cp + ".objects",
                        "缺少必填字段（独立片段必须是新格式：objects 对象）");
            }
            JsonElement objects = obj.get("objects");
            if (!objects.isJsonObject() || objects.getAsJsonObject().size() == 0) {
                throw new ScriptParseException(cp + ".objects",
                        "需要非空对象（独立片段必须是新格式：objects 对象）");
            }
            // track 只属于独立片段表；摘掉后再走内联片段同一条新格式解析路径（startTime=0，不写回 JSON）
            JsonObject clipObj = obj.deepCopy();
            clipObj.remove("track");
            clips.add(parseObjectClip(clipObj, cp, type, 0f));
        }
        return clips;
    }

    /** {@link #normalizeStructs} 的固定路径分类（不做反射扫描）。 */
    private enum StructKind {
        MODIFIER,
        TRANSITION_PARAMS,
        MATH_MODEL,
        COLOR_CURVE
    }

    /**
     * 新格式 {@code objects} 里需要强类型消费方的 STRUCT 路径 → 归一类别（固定表）。
     *
     * <p>未列出的路径（{@code gradient} / {@code data} / {@code lut} / {@code curve} 等）返回 {@code null}：
     * 它们的消费方按原始 {@code Map}/{@code List} 读取，保持原样。</p>
     */
    private static StructKind structKindOf(TrackTypeId type, String path) {
        switch (path) {
            case "modifier":
                return TrackTypeId.CAMERA.equals(type) ? StructKind.MODIFIER : null;
            case "transition_params":
                return TrackTypeId.CAMERA.equals(type) ? StructKind.TRANSITION_PARAMS : null;
            case "transition_curve":
            case "motion_position":
            case "motion_yaw":
            case "motion_pitch":
            case "motion_roll":
            case "motion_fov":
            case "motion_zoom":
                return TrackTypeId.CAMERA.equals(type) ? StructKind.MATH_MODEL : null;
            case "rgb_curve":
            case "r_curve":
            case "g_curve":
            case "b_curve":
            case "hv_h_curve":
            case "hv_s_curve":
            case "hv_l_curve":
            case "lv_s_curve":
            case "sv_s_curve":
            case "sv_l_curve":
                return StructKind.COLOR_CURVE;
            default:
                return null;
        }
    }

    /**
     * 把新格式 {@code objects} 的 STRUCT 值从 {@code ObjectCodec} 产出的原始 {@code Map}/{@code List}
     * 重新解析成强类型对象（{@link ClipModifier} / {@link TransitionParams} / {@link MathModel} /
     * {@link ColorCurve}），供 {@code Clip#getModifier()} / {@code #getTransitionSpec()} /
     * {@code #getColorCurve()} 等直接返回。
     *
     * <p>只处理 {@code mode=value && type=struct} 且写了 {@code value} 的属性；未分类路径、缺 {@code value}
     * 的属性原样保留（含插入顺序）。结构非法照旧抛 {@link ScriptParseException}，错误路径指向
     * {@code <clip>.objects.<path>.value}。</p>
     */
    private static ClipObjects normalizeStructs(ClipObjects objects, JsonObject rawObjects, String p, TrackTypeId type)
            throws ScriptParseException {
        if (objects.isEmpty() || rawObjects == null) {
            return objects;
        }
        Map<String, SampledProperty> rebuilt = null;
        for (Map.Entry<String, SampledProperty> entry : objects.asMap().entrySet()) {
            String path = entry.getKey();
            SampledProperty property = entry.getValue();
            StructKind kind = structKindOf(type, path);
            if (kind == null || property.mode() != PropertyMode.VALUE
                    || property.type() != PropertyType.STRUCT || property.value() == null) {
                continue;
            }
            JsonElement raw = rawObjects.get(path);
            if (raw == null || !raw.isJsonObject() || !raw.getAsJsonObject().has("value")) {
                continue;
            }
            String valuePath = p + ".objects." + path + ".value";
            JsonElement rawValue = raw.getAsJsonObject().get("value");
            Object typed = switch (kind) {
                case MODIFIER -> parseClipModifier(rawValue, valuePath);
                case TRANSITION_PARAMS -> parseTransitionParams(rawValue, valuePath);
                case MATH_MODEL -> parseMathModel(rawValue, valuePath, path);
                case COLOR_CURVE -> parseColorCurve(rawValue, valuePath);
            };
            if (rebuilt == null) {
                rebuilt = new LinkedHashMap<>(Math.max(16, objects.asMap().size() * 2));
                rebuilt.putAll(objects.asMap());
            }
            rebuilt.put(path, new SampledProperty(path, PropertyMode.VALUE, PropertyType.STRUCT,
                    Interp.NONE, typed, List.of()));
        }
        return rebuilt == null ? objects : new ClipObjects(rebuilt);
    }

    /** 新格式 {@code loop_count}：有限整数（-1 = 无限循环，正整数 = 循环次数）。 */
    private static int requireLoopCount(JsonElement value, String p) throws ScriptParseException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(p, "需要整数");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.floor(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ScriptParseException(p, "需要整数");
        }
        return (int) number;
    }

    /** {@link ObjectFormatException#getMessage()} 已带 {@code <fieldPath>: } 前缀，转成解析异常时只取说明部分。 */
    private static String detailOf(ObjectFormatException e) {
        String message = e.getMessage();
        String fieldPath = e.fieldPath();
        if (fieldPath == null) {
            return message;
        }
        String prefix = fieldPath + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    // ========== ColorCurve 解析（ADJUST 轨曲线组，形态 b）==========

    /**
     * 解析 {@code color_curve} 字段 = 控制点数组 {@code [[x,y], ...]}（RGB 复合曲线与每通道曲线共用，
     * 字段名由 {@code p} 带出）。结构在此严格校验（与 {@code ScriptValidator} 同一口径）：数组、
     * 每点 2 个数字、x 严格递增、x / y 各 0~1、至少 2 点；不合法直接抛 {@link ScriptParseException}。
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
     * 形态 {@code {"type": "spring", "stiffness": 8.0}}；组合子定义内联（{@code chain} 的
     * {@code models} 数组 / {@code blend} 的 {@code a}、{@code b}）。值域由字段名决定
     * （{@code motion_position} = VECTOR、{@code motion_yaw} 等 = ANGLE、
     * {@code motion_fov} / {@code motion_zoom} = SCALAR），以 {@code domain} 交给注册表。
     * 定义按归一形态驻留（同形态复用同一对象）；未注册 / 未知参数 / 越界值告警 + 回落 {@code linear}。
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

    // ========== 覆盖链 Modifier 声明解析（CAMERA clip 级 modifier 字段）==========

    /**
     * 解析 clip 级 {@code modifier}（片段级静态 Modifier 声明 → {@link ClipModifier}）：
     * {@code {channels, mode?, priority?, <通道值>}}——结构键 {@code channels} / {@code mode} /
     * {@code priority}，值键 = 通道名本身（{@code position} 是 {@code {x, y, z}}，其余五个是有限数字）。
     * 与 {@code ScriptValidator#checkModifier} 同一口径（校验拦下的写法解析期也会拒绝）。
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
     * 「未声明」标记（由 {@code Clip.getTransitionSpec()} 按片段级缺省补齐：全六通道 BLEND、
     * 同一时长、无曲线 = 线性斜坡、BLEND 打断）。与
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

    // ========== 触发器定义解析 ==========

    private static TriggerDefinition parseTriggerDefinition(JsonObject obj, String p, int index) throws ScriptParseException {
        String type = requireString(obj, p, "type");
        // 声明身份：显式 id 优先（缺省 / null = 由 type#声明下标 合成）。格式与重复归校验器，解析只判类型。
        String id = optTriggerId(obj, p);
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
        ContinuitySpec continuity = parseContinuity(obj, p);
        return new TriggerDefinition(type, conditions, repeatable, delay, onEnter, exitBuffer, requires, pollInterval,
                id, index, continuity);
    }

    /**
     * 解析触发器级连续语义 {@code continuity}（可选）：缺省 / JSON null → {@link ContinuitySpec#DEFAULT}。
     *
     * <p>字段：{@code sweep}（布尔）、{@code dwell} / {@code window}（整数 tick，
     * {@code 0..}{@link ContinuitySpec#MAX_TICKS}）。
     * 未知键静默忽略（向前兼容）。非法形态直接报解析错误——连续语义写错会导致判定与作者意图不符，
     * 静默降级比拒绝加载更难排查。
     */
    private static ContinuitySpec parseContinuity(JsonObject obj, String p) throws ScriptParseException {
        if (!obj.has("continuity") || obj.get("continuity").isJsonNull()) {
            return ContinuitySpec.DEFAULT;
        }
        String cp = p + ".continuity";
        JsonElement el = obj.get("continuity");
        if (!el.isJsonObject()) {
            throw new ScriptParseException(cp, "continuity 必须是对象");
        }
        JsonObject continuity = el.getAsJsonObject();

        boolean sweep = false;
        if (continuity.has("sweep") && !continuity.get("sweep").isJsonNull()) {
            JsonElement se = continuity.get("sweep");
            if (!se.isJsonPrimitive() || !se.getAsJsonPrimitive().isBoolean()) {
                throw new ScriptParseException(cp + ".sweep", "sweep 必须是布尔值");
            }
            sweep = se.getAsBoolean();
        }

        int dwell = parseContinuityTicks(continuity, cp, "dwell");
        int window = parseContinuityTicks(continuity, cp, "window");
        return new ContinuitySpec(sweep, dwell, window);
    }

    /**
     * 解析 {@code continuity} 里的 tick 字段：整数 {@code 0..}{@link ContinuitySpec#MAX_TICKS}；
     * 缺省 / JSON null → 0；非数字 / 非整数 / 越界 → 解析错误。
     */
    private static int parseContinuityTicks(JsonObject continuity, String cp, String field)
            throws ScriptParseException {
        if (!continuity.has(field) || continuity.get(field).isJsonNull()) {
            return 0;
        }
        JsonElement el = continuity.get(field);
        String fp = cp + "." + field;
        String range = "0~" + ContinuitySpec.MAX_TICKS;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(fp, field + " 必须是整数（tick，" + range + "）");
        }
        double v = el.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new ScriptParseException(fp, field + " 必须是整数（tick，" + range + "），实际: " + el);
        }
        if (v < 0 || v > ContinuitySpec.MAX_TICKS) {
            throw new ScriptParseException(fp, field + " 超出范围 " + range + "（tick），实际: " + el);
        }
        return (int) v;
    }

    /** 触发器声明 id：缺省 / JSON null → null；非字符串或空串 → 解析错误（格式校验归 {@code ScriptValidator}）。 */
    private static String optTriggerId(JsonObject obj, String p) throws ScriptParseException {
        if (!obj.has("id") || obj.get("id").isJsonNull()) return null;
        JsonElement el = obj.get("id");
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(p + ".id", "id 必须是字符串");
        }
        String id = el.getAsString();
        if (id.isEmpty()) {
            throw new ScriptParseException(p + ".id", "id 不能为空");
        }
        return id;
    }

    /**
     * 解析触发器前置条件 requires（AND 语义；缺省 = 无前置）。
     * 元素两种写法：字符串 = 前置脚本 id（等价于 {@code {"type":"script_played","script":"id"}}）；
     * 对象 = {@code {"type":"...", ...}}，可注册自定义前置条件。
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

    // ========== 轨道结构校验 ==========

    private static void validateTracks(List<TimelineTrack> tracks, String p) throws ScriptParseException {
        long letterboxCount = tracks.stream().filter(t -> TrackTypeId.LETTERBOX.equals(t.getType())).count();
        if (letterboxCount > 1) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Parse",
                    "检测到 " + letterboxCount + " 条 LETTERBOX 轨道，建议最多1条");
        }
        long eventCount = tracks.stream().filter(t -> TrackTypeId.EVENT.equals(t.getType())).count();
        if (eventCount > 1) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Parse",
                    "检测到 " + eventCount + " 条 EVENT 轨道，建议最多1条");
        }

        for (TimelineTrack track : tracks) {
            if (TrackTypeId.CAMERA.equals(track.getType())) {
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

    private static TrackTypeId parseTrackType(String value, String p) throws ScriptParseException {
        TrackTypeId id = TrackRegistry.resolve(value);
        if (id == null) {
            throw new ScriptParseException(p, "未知的轨道类型: " + value
                    + "，支持: " + TrackRegistry.idListForMessage());
        }
        return id;
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

    // 未知字段：递归解析为 Map（向前兼容）
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

    /**
     * 可选轮询间隔（tick，{@link TriggerType#POLL_INTERVAL_MIN}~{@link TriggerType#POLL_INTERVAL_MAX}）：
     * 缺省 / JSON null → null（回落到下一级）；非整数 / 越界 → 告警 + null（宽松防御式解析，与 skip_vote_ratio 的严格解析不同——后者非法即报错）。
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

    /**
     * 解析 meta.screen_layers：{@code {层名: 布尔}}，极性 = 「显示」
     * （{@code true} = 显示，{@code false} = 隐藏；与 {@code ScriptMeta.RuntimeBehavior#screenLayers()} 一致）。
     *
     * <p>已知层名按 {@link ScreenLayer#canonicalize} 归一；其余键保留原始形态，继续按动态层名参与判定
     * （{@code health} / {@code status} / 未注册 overlay 资源 ID 等）。</p>
     */
    private static java.util.Map<String, Boolean> parseScreenLayers(JsonObject metaObj) {
        java.util.Map<String, Boolean> layers = new java.util.LinkedHashMap<>();
        if (metaObj.has("screen_layers") && !metaObj.get("screen_layers").isJsonNull()) {
            JsonElement el = metaObj.get("screen_layers");
            if (!el.isJsonObject()) {
                ErrorLog.log("Parse", "meta.screen_layers 需要对象 {层名: 布尔}（true = 显示，false = 隐藏），实际: "
                        + el + "，已忽略");
            } else {
                for (java.util.Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    String key = ScreenLayer.canonicalize(e.getKey());
                    if (key == null || key.isEmpty()) {
                        ErrorLog.log("Parse", "meta.screen_layers 存在空层名，已忽略");
                        continue;
                    }
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                        // 重复规范化键（如 "chat" 与 "hud.chat" 并存）：后者覆盖
                        layers.put(key, v.getAsBoolean());
                    } else {
                        ErrorLog.log("Parse", "meta.screen_layers['" + e.getKey()
                                + "'] 需要布尔值（true = 显示，false = 隐藏），实际: " + v + "，已忽略该键");
                    }
                }
            }
        }
        return layers;
    }

    /**
     * 归一 {@code meta.screen_layer_mode}（解析期，结果非 null）：
     * <pre>
     * 显式 screen_layer_mode ?? screen_layers 字段存在 → allowlist ?? normal
     * </pre>
     * 非法声明按「未声明」处理并告警。
     */
    private static ScreenLayerMode resolveScreenLayerMode(JsonObject metaObj) {
        ScreenLayerMode declared = null;
        if (metaObj.has("screen_layer_mode") && !metaObj.get("screen_layer_mode").isJsonNull()) {
            JsonElement el = metaObj.get("screen_layer_mode");
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                ErrorLog.log("Parse", "meta.screen_layer_mode 需要字符串（normal / allowlist），实际: "
                        + el + "，按未声明处理");
            } else if (!ScreenLayerMode.isKnown(el.getAsString())) {
                ErrorLog.log("Parse", "meta.screen_layer_mode 非法值: " + el.getAsString()
                        + "（可选: normal / allowlist），按未声明处理");
            } else {
                declared = ScreenLayerMode.fromString(el.getAsString());
            }
        }

        if (declared != null) return declared;
        // 新字段路径缺省 allowlist：字段存在（非 JSON null）且无模式声明 → 接管模式
        if (metaObj.has("screen_layers") && !metaObj.get("screen_layers").isJsonNull()) {
            return ScreenLayerMode.ALLOWLIST;
        }
        return ScreenLayerMode.NORMAL;
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
