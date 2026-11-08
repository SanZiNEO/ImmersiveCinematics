package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.camera.rule.RuleActions;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionMode;
import com.immersivecinematics.immersive_cinematics.client.rule.ClientConditions;
import com.immersivecinematics.immersive_cinematics.math.ValueDomain;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitKind;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipIdentity;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectValidator;
import com.immersivecinematics.immersive_cinematics.script.path.PathCollectionParser;
import com.immersivecinematics.immersive_cinematics.script.rule.ClipExitPolicyParser;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleCollectionParser;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleControl;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleDefinition;
import com.immersivecinematics.immersive_cinematics.script.schema.MathModelRegistry;
import com.immersivecinematics.immersive_cinematics.script.schema.PathCurveException;
import com.immersivecinematics.immersive_cinematics.script.schema.PathCurveRegistry;
import com.immersivecinematics.immersive_cinematics.script.schema.TriggerSchemas;
import com.immersivecinematics.immersive_cinematics.script.track.TrackRegistry;
import com.immersivecinematics.immersive_cinematics.selector.SelectorSchema;
import com.immersivecinematics.immersive_cinematics.spi.FieldDef;
import com.immersivecinematics.immersive_cinematics.spi.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.spi.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 脚本静态校验器 — 供 /icinematics validate 命令使用。
 *
 * 与 {@link ScriptParser} 的区别：解析器遇错即停（运行时拒载），本校验器收集全部问题继续扫描，
 * 一次输出完整清单（结构错误 / 字段缺失 / 语义错误 / 缺省字段提示）。
 * 面向 AI 仅凭参考指南写脚本的场景：拿到清单即可逐条修正。
 * 校验口径与解析器同源（同一口径）：校验拦下的写法解析期也会拒绝；校验器另有更严的结构语义
 * 提示（见 {@code checkScriptRuleCollection}）。失败只返回 issue 列表，不抛异常。
 */
public final class ScriptValidator {

    /** 触发器声明 id 的格式判定（正则本身由 {@link TriggerDefinition#ID_REGEX} 定义）；延迟编译一次。 */
    private static final Pattern TRIGGER_ID_PATTERN = Pattern.compile(TriggerDefinition.ID_REGEX);

    /**
     * 警告级 issue 前缀：结构合法、可以加载，但运行期会明显不达预期。
     * 报告里与错误区分；{@link #errorsOf} 过滤掉警告后为空即视为校验通过。
     */
    public static final String WARNING_PREFIX = "[警告] ";

    /** 是否为警告级 issue。 */
    public static boolean isWarning(String issue) {
        return issue != null && issue.startsWith(WARNING_PREFIX);
    }

    /** 只保留错误级 issue（去掉警告级）。 */
    public static List<String> errorsOf(List<String> issues) {
        if (issues == null || issues.isEmpty()) return List.of();
        List<String> errors = new ArrayList<>(issues.size());
        for (String issue : issues) {
            if (!isWarning(issue)) errors.add(issue);
        }
        return errors;
    }

    /**
     * 校验脚本 JSON 文本，返回问题清单（空 = 校验通过）。
     *
     * @param json 脚本文件内容
     * @return 问题列表，每条含路径与说明；无问题返回空列表
     */
    public static List<String> validate(String json) {
        return validate(json, null);
    }

    /**
     * 校验脚本 JSON 文本（可附带已知脚本 id 集合做跨脚本引用检查）。
     *
     * @param json            脚本文件内容
     * @param knownScriptIds  当前已加载的全部脚本 id（null = 不做跨脚本 exists 检查）
     * @return 问题列表，每条含路径与说明；无问题返回空列表
     */
    public static List<String> validate(String json, Collection<String> knownScriptIds) {
        List<String> issues = new ArrayList<>();

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            issues.add("JSON 解析失败: " + e.getMessage());
            return issues;
        }

        // ===== meta =====
        if (!root.has("meta") || !root.get("meta").isJsonObject()) {
            issues.add("meta 缺失或不是对象（需要 id/name/author）");
        } else {
            JsonObject meta = root.getAsJsonObject("meta");
            rejectUnknownFields(meta, "meta", ScriptParser.META_KEYS, issues);
            requireString(meta, "meta", "id", issues);
            requireString(meta, "meta", "name", issues);
            requireString(meta, "meta", "author", issues);
            // 长度 / 格式硬限制（与 ScriptParser#parseMeta 同一口径——校验器放过的写法解析期会直接拒载脚本）
            if (meta.has("id") && meta.get("id").isJsonPrimitive() && meta.get("id").getAsJsonPrimitive().isString()) {
                String id = meta.get("id").getAsString();
                if (!id.isEmpty() && !id.matches("^[a-zA-Z0-9_]{1,32}$")) {
                    issues.add("meta.id 必须匹配 ^[a-zA-Z0-9_]{1,32}$（1~32 位字母 / 数字 / 下划线），实际: " + id);
                }
            }
            checkMaxLength(meta, "meta", "name", 50, issues);
            checkMaxLength(meta, "meta", "author", 30, issues);
        }

        // ===== meta 宏观循环（macro_loop）=====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            // macro_loop_count：-1 = 无限重复；正整数 N = 从 a 到 b 重复 N 圈后自然结束
            if (meta.has("macro_loop_count")) {
                try {
                    int count = meta.get("macro_loop_count").getAsInt();
                    if (count == 0) {
                        issues.add("meta.macro_loop_count 不允许为 0（1 = 只播一遍，不重复），运行时按 1 处理");
                    } else if (count < -1) {
                        issues.add("meta.macro_loop_count 只能为 -1（无限重复）或正整数，实际: " + count
                                + "，运行时按 -1 处理");
                    }
                } catch (Exception e) {
                    issues.add("meta.macro_loop_count 不是整数");
                }
            }
            boolean macroLoop = meta.has("macro_loop") && meta.get("macro_loop").isJsonPrimitive()
                    && meta.get("macro_loop").getAsJsonPrimitive().isBoolean()
                    && meta.get("macro_loop").getAsBoolean();
            if (macroLoop) {
                boolean holdAtEnd = meta.has("hold_at_end") && meta.get("hold_at_end").isJsonPrimitive()
                        && meta.get("hold_at_end").getAsJsonPrimitive().isBoolean()
                        && meta.get("hold_at_end").getAsBoolean();
                if (holdAtEnd) {
                    issues.add("meta.macro_loop 与 meta.hold_at_end 同时开启（互斥）：无限次数"
                            + "（macro_loop_count=-1，缺省）下脚本不自然结束，hold_at_end 不会生效，请二选一");
                }
                String mode = meta.has("macro_loop_mode") && meta.get("macro_loop_mode").isJsonPrimitive()
                        ? meta.get("macro_loop_mode").getAsString() : "repeat";
                if ("pingpong".equals(mode)) {
                    issues.add("meta.macro_loop_mode=pingpong 第一版未实现：按 repeat 播放");
                } else if (!"repeat".equals(mode)) {
                    issues.add("meta.macro_loop_mode 未知值: " + mode + "（可选: repeat / pingpong）");
                }
            }
        }

        // ===== meta.play_mode：跨脚本播放关系（三态枚举，缺省 parallel）=====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            if (meta.has("play_mode")) {
                JsonElement playMode = meta.get("play_mode");
                if (!isString(playMode)) {
                    issues.add("meta.play_mode 需要字符串（parallel / exclusive / interruptible）");
                } else if (!ScriptMeta.PlayMode.isKnown(playMode.getAsString())) {
                    issues.add("meta.play_mode 未知值: " + playMode.getAsString()
                            + "（可选: parallel / exclusive / interruptible），运行时按 parallel 处理");
                }
            }
        }

        // ===== meta.skip_vote_ratio：整数百分比 10~100（与 ScriptParser 同口径，解析期即报错）=====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            if (meta.has("skip_vote_ratio") && !meta.get("skip_vote_ratio").isJsonNull()) {
                JsonElement ratioEl = meta.get("skip_vote_ratio");
                if (!ratioEl.isJsonPrimitive() || !ratioEl.getAsJsonPrimitive().isNumber()) {
                    issues.add("meta.skip_vote_ratio 必须为整数（百分比 10~100），实际: " + ratioEl);
                } else {
                    double ratioValue = ratioEl.getAsDouble();
                    if (!Double.isFinite(ratioValue) || ratioValue != Math.floor(ratioValue)) {
                        issues.add("meta.skip_vote_ratio 必须为整数（百分比 10~100），实际: " + ratioEl);
                    } else if (ratioValue < 10 || ratioValue > 100) {
                        issues.add("meta.skip_vote_ratio 取值范围 10~100（不允许 0），实际: " + ratioEl);
                    }
                }
            }
        }

        // ===== meta.base_resolution：编辑基准分辨率（分辨率转义系数 k 的分母）=====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            checkBaseResolution(root.getAsJsonObject("meta"), issues);
        }

        // ===== meta.screen_layers：画面层唯一入口 =====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            checkScreenLayers(root, issues);
        }

        // ===== meta.triggers：前置依赖（requires）+ 类型/条件结构校验 =====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            checkPollIntervalField(meta, "trigger_poll_interval", "meta", issues);
            validateTriggerRequires(meta, "meta", knownScriptIds, issues);
            validateTriggerIdentities(meta, "meta", issues);
            validateTriggerConditions(meta, "meta", knownScriptIds, issues);
        }

        // ===== 片段身份：id 格式 / 脚本内唯一性 / 独立片段结构 =====
        // 放在 timeline 检查之前：timeline 缺失时也能报出独立片段表的问题。
        checkClipIdentities(root, issues);

        // ===== 脚本级规则集合：rules[] / rule_bindings[] =====
        checkScriptRuleCollection(root, issues);

        // ===== 脚本级路径集合：paths[]（与解析器同一套结构 / 端点规则）=====
        checkPathCollection(root, issues);

        // ===== 片段级 exit：结构校验 =====
        checkClipExits(root, issues);

        // ===== timeline =====
        if (!root.has("timeline") || !root.get("timeline").isJsonObject()) {
            issues.add("timeline 缺失或不是对象");
            return issues;
        }
        JsonObject timeline = root.getAsJsonObject("timeline");
        rejectUnknownFields(timeline, "timeline", ScriptParser.TIMELINE_KEYS, issues);
        if (!timeline.has("tracks") || !timeline.get("tracks").isJsonArray()) {
            issues.add("timeline.tracks 缺失或不是数组");
            return issues;
        }

        // ===== 逐轨道 =====
        JsonArray tracks = timeline.getAsJsonArray("tracks");
        for (int ti = 0; ti < tracks.size(); ti++) {
            JsonElement te = tracks.get(ti);
            if (!te.isJsonObject()) {
                issues.add("timeline.tracks[" + ti + "] 不是对象");
                continue;
            }
            JsonObject track = te.getAsJsonObject();
            String tp = "timeline.tracks[" + ti + "]";
            String type = null;
            if (!track.has("type")) {
                issues.add(tp + ".type 缺失");
            } else {
                type = track.get("type").getAsString();
                if (!isKnownType(type)) {
                    issues.add(tp + ".type 未知类型: " + type
                            + "（可选: " + trackTypesHint() + "）");
                }
            }

            if (!track.has("clips") || !track.get("clips").isJsonArray()) {
                issues.add(tp + ".clips 缺失或不是数组（轨道需要 clips 数组）");
                continue;
            }

            JsonArray clips = track.getAsJsonArray("clips");
            for (int ci = 0; ci < clips.size(); ci++) {
                JsonElement ce = clips.get(ci);
                if (!ce.isJsonObject()) {
                    issues.add(tp + ".clips[" + ci + "] 不是对象");
                    continue;
                }
                JsonObject clip = ce.getAsJsonObject();
                String cp = tp + ".clips[" + ci + "]";

                float start = 0f, dur = 0f;
                if (!clip.has("start_time")) issues.add(cp + ".start_time 缺失");
                else {
                    try {
                        start = clip.get("start_time").getAsFloat();
                        if (!Float.isFinite(start)) issues.add(cp + ".start_time 不是有限数字（NaN / Infinity）");
                    }
                    catch (Exception e) { issues.add(cp + ".start_time 不是数字"); }
                }
                if (!clip.has("duration")) issues.add(cp + ".duration 缺失");
                else {
                    try {
                        float d = clip.get("duration").getAsFloat();
                        if (!Float.isFinite(d)) {
                            issues.add(cp + ".duration 不是有限数字（NaN / Infinity）");
                        } else if (d == 0f) {
                            issues.add(cp + ".duration 不允许为 0（正数=定长，负数=无限）");
                        }
                        dur = d;
                    } catch (Exception e) { issues.add(cp + ".duration 不是数字"); }
                }

                ObjectValidator.validate(type == null ? null : trackTypeOf(type), clip, cp, issues);
            }
        }
        return issues;
    }

    // ======================================================================
    // 片段身份（I4-1）：id 格式 / 脚本内唯一性 / 独立片段结构
    // ======================================================================

    /**
     * 校验片段身份与脚本级独立片段表：
     * <ul>
     *   <li>{@code id} 格式（{@link ClipIdentity#PATTERN}）；</li>
     *   <li>脚本内唯一性——内联片段与独立片段共用同一命名空间，重复时指出两处路径；</li>
     *   <li>独立片段结构：{@code track} 必填合法、{@code duration} 必填非 0、{@code objects} 必填非空、
     *       禁止 {@code start_time}（没有默认放置）。</li>
     * </ul>
     *
     * <p>缺 {@code id} 不报错：未被任何宿主引用的片段可以匿名。「被引用却无 id」属于引用检查，
     * 由规则 / 路径 / 出口的后续轮次加入。解析期不报重复（交给本校验器），避免解析基线漂移。</p>
     */
    private static void checkClipIdentities(JsonObject root, List<String> issues) {
        Map<String, String> firstSeen = new LinkedHashMap<>();

        // 内联片段：timeline.tracks[ti].clips[ci]
        JsonElement timelineElement = root.get("timeline");
        if (timelineElement != null && timelineElement.isJsonObject()) {
            JsonElement tracksElement = timelineElement.getAsJsonObject().get("tracks");
            if (tracksElement != null && tracksElement.isJsonArray()) {
                JsonArray tracks = tracksElement.getAsJsonArray();
                for (int ti = 0; ti < tracks.size(); ti++) {
                    JsonElement te = tracks.get(ti);
                    if (!te.isJsonObject()) continue;
                    JsonElement clipsElement = te.getAsJsonObject().get("clips");
                    if (clipsElement == null || !clipsElement.isJsonArray()) continue;
                    JsonArray clips = clipsElement.getAsJsonArray();
                    for (int ci = 0; ci < clips.size(); ci++) {
                        JsonElement ce = clips.get(ci);
                        if (!ce.isJsonObject()) continue;
                        checkClipIdField(ce.getAsJsonObject(),
                                "timeline.tracks[" + ti + "].clips[" + ci + "]", firstSeen, issues);
                    }
                }
            }
        }

        // 独立片段：clips[i]
        if (!root.has("clips")) return;
        JsonElement clipsElement = root.get("clips");
        if (!clipsElement.isJsonArray()) {
            issues.add("clips 需要数组（脚本级独立片段表；缺省 = 无独立片段）");
            return;
        }
        JsonArray clips = clipsElement.getAsJsonArray();
        for (int i = 0; i < clips.size(); i++) {
            String cp = "clips[" + i + "]";
            JsonElement ce = clips.get(i);
            if (!ce.isJsonObject()) {
                issues.add(cp + " 需要对象（独立片段）");
                continue;
            }
            JsonObject clip = ce.getAsJsonObject();
            checkClipIdField(clip, cp, firstSeen, issues);
            if (clip.has("start_time")) {
                issues.add(cp + ".start_time 不允许（独立片段没有默认放置；"
                        + "需要放置请写 timeline.tracks[].clips[] 的内联片段）");
            }
            checkStandaloneTrack(clip, cp, issues);
            checkStandaloneDuration(clip, cp, issues);
            checkStandaloneObjects(clip, cp, issues);
        }
    }

    /** 片段级 {@code exit}（片段默认出口）：按 {@link #checkExitStructure} 校验结构。 */
    private static void checkClipExits(JsonObject root, List<String> issues) {
        Set<String> clipIds = new LinkedHashSet<>();
        Map<String, Float> durations = new LinkedHashMap<>();
        collectClipDeclarations(root, clipIds, durations);

        JsonElement timelineElement = root.get("timeline");
        if (timelineElement != null && timelineElement.isJsonObject()) {
            JsonElement tracksElement = timelineElement.getAsJsonObject().get("tracks");
            if (tracksElement != null && tracksElement.isJsonArray()) {
                JsonArray tracks = tracksElement.getAsJsonArray();
                for (int ti = 0; ti < tracks.size(); ti++) {
                    JsonElement te = tracks.get(ti);
                    if (!te.isJsonObject()) continue;
                    JsonElement clipsElement = te.getAsJsonObject().get("clips");
                    if (clipsElement == null || !clipsElement.isJsonArray()) continue;
                    JsonArray clips = clipsElement.getAsJsonArray();
                    for (int ci = 0; ci < clips.size(); ci++) {
                        JsonElement ce = clips.get(ci);
                        if (!ce.isJsonObject()) continue;
                        checkClipExitField(ce.getAsJsonObject(),
                                "timeline.tracks[" + ti + "].clips[" + ci + "]", clipIds, issues);
                    }
                }
            }
        }

        JsonElement standalone = root.get("clips");
        if (standalone == null || !standalone.isJsonArray()) return;
        JsonArray clips = standalone.getAsJsonArray();
        for (int i = 0; i < clips.size(); i++) {
            JsonElement ce = clips.get(i);
            if (!ce.isJsonObject()) continue;
            checkClipExitField(ce.getAsJsonObject(), "clips[" + i + "]", clipIds, issues);
        }
    }

    /** 单个片段的 {@code exit} 字段：必须是对象并通过结构校验。 */
    private static void checkClipExitField(JsonObject clip, String cp, Set<String> clipIds,
                                           List<String> issues) {
        JsonElement exit = clip.get("exit");
        if (exit == null) return;
        if (!clip.has("objects")) {
            issues.add(cp + ".exit 缺少 objects（新格式片段必须声明对象）");
            return;
        }
        if (!exit.isJsonObject()) {
            issues.add(cp + ".exit 需要对象（出口策略）");
            return;
        }
        checkExitStructure(exit.getAsJsonObject(), cp + ".exit", clipIds, issues);
    }

    /** 一个片段的 {@code id}：类型 / 格式 / 脚本内唯一（内联与独立共用命名空间）。 */
    private static void checkClipIdField(JsonObject clip, String cp, Map<String, String> firstSeen,
                                         List<String> issues) {
        if (!clip.has("id")) return;
        JsonElement idElement = clip.get("id");
        if (!isString(idElement)) {
            issues.add(cp + ".id 需要字符串（片段 id，匹配 " + ClipIdentity.PATTERN + "）");
            return;
        }
        String id = idElement.getAsString();
        if (!ClipIdentity.isValid(id)) {
            issues.add(cp + ".id 不合法: " + id + "（需匹配 " + ClipIdentity.PATTERN + "）");
            return;
        }
        String previous = firstSeen.putIfAbsent(id, cp);
        if (previous != null) {
            issues.add("重复的片段 id: " + id + "（出现于 " + previous + " 与 " + cp + "）");
        }
    }

    /** 独立片段 {@code track}：必填、字符串、已知轨道类型（决定 {@code objects} schema）。 */
    private static void checkStandaloneTrack(JsonObject clip, String cp, List<String> issues) {
        if (!clip.has("track")) {
            issues.add(cp + ".track 缺失（独立片段需要轨道类型，决定 objects schema）");
            return;
        }
        JsonElement track = clip.get("track");
        if (!isString(track)) {
            issues.add(cp + ".track 需要字符串（轨道类型名: " + trackTypesHint() + "）");
            return;
        }
        if (trackTypeOf(track.getAsString()) == null) {
            issues.add(cp + ".track 未知类型: " + track.getAsString() + "（可选: " + trackTypesHint() + "）");
        }
    }

    /** 独立片段 {@code duration}：必填、有限、非 0（负数 = 无限时长）。 */
    private static void checkStandaloneDuration(JsonObject clip, String cp, List<String> issues) {
        if (!clip.has("duration")) {
            issues.add(cp + ".duration 缺失（正数=有限时长，负数=无限时长）");
            return;
        }
        JsonElement duration = clip.get("duration");
        if (!isNumber(duration)) {
            issues.add(cp + ".duration 不是数字");
            return;
        }
        float d = duration.getAsFloat();
        if (!Float.isFinite(d)) {
            issues.add(cp + ".duration 不是有限数字（NaN / Infinity）");
        } else if (d == 0f) {
            issues.add(cp + ".duration 不允许为 0（正数=有限时长，负数=无限时长）");
        }
    }

    /** 独立片段 {@code objects}：必填、对象、非空（独立片段必须是新格式）。 */
    private static void checkStandaloneObjects(JsonObject clip, String cp, List<String> issues) {
        if (!clip.has("objects")) {
            issues.add(cp + ".objects 缺失（独立片段必须是新格式：objects 对象）");
            return;
        }
        JsonElement objects = clip.get("objects");
        if (!objects.isJsonObject()) {
            issues.add(cp + ".objects 需要对象（独立片段必须是新格式）");
        } else if (objects.getAsJsonObject().size() == 0) {
            issues.add(cp + ".objects 为空（独立片段必须是新格式：至少一个采样属性）");
        }
    }

    // ======================================================================
    // 脚本级规则集合（I4-2）：rules[] / rule_bindings[]
    // ======================================================================

    /**
     * 校验脚本级规则集合（{@code rules} 定义表 + {@code rule_bindings} 绑定表）。
     *
     * <p>口径（{@code ROUND-I-4-MAP} §1.2–§1.5、§1.7）：
     * <ul>
     *   <li>定义：id 唯一 / 合法、{@code condition} 对象、{@code actions} 非空数组、{@code policy} 键白名单；</li>
     *   <li>条件树：v1 判定源 + 组合器 + 事实叶子（{@code clip_fact} / {@code rule_fact}），引用须可解析；</li>
     *   <li>绑定：id 唯一、{@code rule} / {@code clip} 可解析、{@code gate.at} 数值且在片段时长范围内、
     *       {@code control} 枚举、{@code schedule} / {@code exit} 结构（不构造 {@link Clip} 对象）；</li>
     *   <li>动作：{@code goto_clip} 用新形态（{@code clip} + {@code entry_time}，拒绝旧 {@code track} / {@code at}），
     *       其余复用 v1 动作校验。</li>
     * </ul>
     *
     * <p>错误路径前缀用 {@code rules[i]} / {@code rule_bindings[i]}（与片段内校验的 {@code cp} 风格一致）。</p>
     */
    private static void checkScriptRuleCollection(JsonObject root, List<String> issues) {
        boolean hasRules = root.has("rules");
        boolean hasBindings = root.has("rule_bindings");
        if (!hasRules && !hasBindings) return;

        Set<String> clipIds = new LinkedHashSet<>();
        Map<String, Float> clipDurations = new LinkedHashMap<>();
        collectClipDeclarations(root, clipIds, clipDurations);

        // ===== 规则定义表 =====
        Set<String> ruleIds = new LinkedHashSet<>();
        List<JsonObject> pendingConditions = new ArrayList<>();
        List<String> pendingConditionPaths = new ArrayList<>();
        List<JsonElement> pendingActions = new ArrayList<>();
        List<String> pendingActionPaths = new ArrayList<>();

        if (hasRules) {
            JsonElement rulesElement = root.get("rules");
            if (!rulesElement.isJsonArray()) {
                issues.add("rules 需要数组（规则定义表；缺省 = 无规则）");
            } else {
                JsonArray array = rulesElement.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    String rp = "rules[" + i + "]";
                    JsonElement entry = array.get(i);
                    if (!entry.isJsonObject()) {
                        issues.add(rp + " 需要对象（规则定义）");
                        continue;
                    }
                    JsonObject rule = entry.getAsJsonObject();
                    rejectKeys(rule, rp, issues, "id", "condition", "actions", "policy");
                    checkRuleIdV2(rule, rp, ruleIds, issues);

                    if (!rule.has("condition")) {
                        issues.add(rp + ".condition 缺失（条件树对象必填）");
                    } else if (!rule.get("condition").isJsonObject()) {
                        issues.add(rp + ".condition 需要对象（条件树）");
                    } else {
                        pendingConditions.add(rule.getAsJsonObject("condition"));
                        pendingConditionPaths.add(rp);
                    }

                    if (!rule.has("actions")) {
                        issues.add(rp + ".actions 缺失（规则至少一个动作）");
                    } else if (!rule.get("actions").isJsonArray()) {
                        issues.add(rp + ".actions 需要数组（元素 = 动作对象）");
                    } else if (rule.getAsJsonArray("actions").isEmpty()) {
                        issues.add(rp + ".actions 为空（规则至少一个动作）");
                    } else {
                        pendingActions.add(rule.get("actions"));
                        pendingActionPaths.add(rp + ".actions");
                    }

                    if (rule.has("policy")) {
                        checkRulePolicy(rule.get("policy"), rp + ".policy", clipIds, issues);
                    }
                }
            }
        }

        // ===== 绑定表（先收 id：fact 调度允许前向引用）=====
        Set<String> bindingIds = new LinkedHashSet<>();
        if (hasBindings) {
            JsonElement bindingsElement = root.get("rule_bindings");
            if (!bindingsElement.isJsonArray()) {
                issues.add("rule_bindings 需要数组（规则绑定表；缺省 = 无绑定）");
            } else {
                JsonArray array = bindingsElement.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    String bp = "rule_bindings[" + i + "]";
                    JsonElement entry = array.get(i);
                    if (!entry.isJsonObject()) {
                        issues.add(bp + " 需要对象（规则绑定）");
                        continue;
                    }
                    collectBindingId(entry.getAsJsonObject(), bp, bindingIds, issues);
                }
                Map<String, JsonObject> ruleObjects = collectRuleObjects(root);
                Map<String, JsonObject> clipObjects = new LinkedHashMap<>();
                collectClipObjects(root, clipObjects);
                for (int i = 0; i < array.size(); i++) {
                    JsonElement entry = array.get(i);
                    if (!entry.isJsonObject()) continue;
                    checkRuleBinding(entry.getAsJsonObject(), "rule_bindings[" + i + "]",
                            ruleIds, bindingIds, clipIds, clipDurations, issues);
                    checkHoldReachability(entry.getAsJsonObject(), "rule_bindings[" + i + "]",
                            ruleObjects, clipObjects, issues);
                }
            }
        }

        // ===== 条件树 + 动作（需要两套引用 id 集全部就绪）=====
        for (int i = 0; i < pendingConditions.size(); i++) {
            checkRuleConditionV2(pendingConditions.get(i), pendingConditionPaths.get(i) + ".condition",
                    issues, bindingIds, clipIds);
        }
        for (int i = 0; i < pendingActions.size(); i++) {
            checkActionArray(pendingActions.get(i), pendingActionPaths.get(i), true, issues, clipIds);
        }
    }

    /** 片段 id 表 + 时长表（内联 + 顶层 {@code clips[]}；非法 id / 时长由 {@link #checkClipIdentities} 报）。 */
    private static void collectClipDeclarations(JsonObject root, Set<String> clipIds,
                                                Map<String, Float> durations) {
        JsonElement timelineElement = root.get("timeline");
        if (timelineElement != null && timelineElement.isJsonObject()) {
            JsonElement tracksElement = timelineElement.getAsJsonObject().get("tracks");
            if (tracksElement != null && tracksElement.isJsonArray()) {
                for (JsonElement trackElement : tracksElement.getAsJsonArray()) {
                    if (!trackElement.isJsonObject()) continue;
                    JsonElement clipsElement = trackElement.getAsJsonObject().get("clips");
                    if (clipsElement == null || !clipsElement.isJsonArray()) continue;
                    for (JsonElement clipElement : clipsElement.getAsJsonArray()) {
                        registerClipDeclaration(clipElement, clipIds, durations);
                    }
                }
            }
        }
        JsonElement standalone = root.get("clips");
        if (standalone != null && standalone.isJsonArray()) {
            for (JsonElement clipElement : standalone.getAsJsonArray()) {
                registerClipDeclaration(clipElement, clipIds, durations);
            }
        }
    }

    private static void registerClipDeclaration(JsonElement clipElement, Set<String> clipIds,
                                                Map<String, Float> durations) {
        if (clipElement == null || !clipElement.isJsonObject()) return;
        JsonObject clip = clipElement.getAsJsonObject();
        JsonElement idElement = clip.get("id");
        if (idElement == null || !isString(idElement) || idElement.getAsString().isEmpty()) return;
        String id = idElement.getAsString();
        clipIds.add(id);
        JsonElement durationElement = clip.get("duration");
        if (durationElement == null || !isNumber(durationElement)) return;
        float duration = durationElement.getAsFloat();
        if (Float.isFinite(duration)) {
            durations.putIfAbsent(id, duration);
        }
    }

    // ========== 脚本级路径集合 paths[] ==========

    /**
     * 校验脚本级路径集合 {@code paths[]}：结构与语义规则全部来自 {@link PathCollectionParser}
     * （id 格式 / 唯一、clip 可解析、from/to 有限且 {@code from < to}、axes / geometry / timing 结构、
     * 端点命中 position 节点时间且相邻、端点对唯一）；解析器抛异常处这里收成 issue，不抛异常。
     */
    private static void checkPathCollection(JsonObject root, List<String> issues) {
        if (!root.has("paths")) return;
        JsonElement pathsElement = root.get("paths");
        if (!pathsElement.isJsonArray()) {
            issues.add("paths 需要数组（路径集合；缺省 = 无路径）");
            return;
        }
        JsonArray array = pathsElement.getAsJsonArray();
        if (array.isEmpty()) return;

        Map<String, JsonObject> clips = new LinkedHashMap<>();
        collectClipObjects(root, clips);

        Set<String> pathIds = new LinkedHashSet<>();
        Map<String, String> endpointPairs = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            String pp = "paths[" + i + "]";
            JsonElement entryElement = array.get(i);
            if (entryElement == null || !entryElement.isJsonObject()) {
                issues.add(pp + " 需要对象（路径条目）");
                continue;
            }
            checkPathEntry(entryElement.getAsJsonObject(), pp, clips, pathIds, endpointPairs, issues);
        }
    }

    /** 单条路径条目：逐字段收集 issue（与 {@link PathCollectionParser#parse} 同一规则集）。 */
    private static void checkPathEntry(JsonObject entry, String pp, Map<String, JsonObject> clips,
                                       Set<String> pathIds, Map<String, String> endpointPairs,
                                       List<String> issues) {
        rejectKeys(entry, pp, issues, "id", "clip", "from", "to", "axes", "geometry", "timing");

        // ===== id：存在 / 字符串 / 格式 / 唯一 =====
        JsonElement idElement = entry.get("id");
        if (idElement == null) {
            issues.add(pp + ".id 缺失（需要字符串）");
        } else if (!isString(idElement) || idElement.getAsString().isEmpty()) {
            issues.add(pp + ".id 需要非空字符串（路径 id）");
        } else {
            String id = idElement.getAsString();
            if (!PathCollectionParser.isValidId(id)) {
                issues.add(pp + ".id 必须匹配 " + PathCollectionParser.ID_PATTERN + "，实际: " + id);
            } else if (!pathIds.add(id)) {
                issues.add(pp + ".id 路径 id 重复: " + id + "（脚本内必须唯一）");
            }
        }

        // ===== clip：存在 / 字符串 / 可解析 / objects 片段 =====
        String clipId = null;
        JsonObject clip = null;
        JsonElement clipElement = entry.get("clip");
        if (clipElement == null) {
            issues.add(pp + ".clip 缺失（需要片段 id 字符串）");
        } else if (!isString(clipElement) || clipElement.getAsString().isEmpty()) {
            issues.add(pp + ".clip 需要非空字符串（片段 id）");
        } else {
            clipId = clipElement.getAsString();
            clip = clips.get(clipId);
            if (clip == null) {
                issues.add(pp + ".clip 未知片段引用: " + clipId + "（宿主 " + pp + "）");
            } else if (!hasObjects(clip)) {
                issues.add(pp + ".clip 路径集合只支持 objects 片段（目标片段 " + clipId + " 没有 objects）");
            }
        }

        // ===== from / to：存在 / 有限 / from < to =====
        Float from = readPathTime(entry.get("from"), pp + ".from", issues);
        Float to = readPathTime(entry.get("to"), pp + ".to", issues);
        boolean ordered = from != null && to != null && from < to;
        if (from != null && to != null && !ordered) {
            issues.add(pp + ".to to 必须大于 from（from=" + from + ", to=" + to + "）");
        }

        // ===== axes / geometry / timing：结构归解析器同一入口 =====
        if (entry.has("axes")) {
            try {
                PathCollectionParser.parseAxes(entry.get("axes"), pp + ".axes");
            } catch (ScriptParser.ScriptParseException e) {
                issues.add(e.getMessage());
            }
        }
        try {
            PathCollectionParser.parseGeometry(entry.get("geometry"), pp + ".geometry");
        } catch (ScriptParser.ScriptParseException e) {
            issues.add(e.getMessage());
        }
        if (entry.has("timing")) {
            try {
                PathCollectionParser.parseTiming(entry.get("timing"), pp + ".timing");
            } catch (ScriptParser.ScriptParseException e) {
                issues.add(e.getMessage());
            }
        }

        // ===== 端点：命中 position 节点时间且相邻；端点对唯一 =====
        if (clip == null || !ordered || !hasObjects(clip)) return;
        JsonElement objectsElement = clip.get("objects");
        float[] nodeTimes = PathCollectionParser.positionNodeTimes(objectsElement.getAsJsonObject());
        int fromIndex = PathCollectionParser.indexOfTime(nodeTimes, from);
        int toIndex = PathCollectionParser.indexOfTime(nodeTimes, to);
        String available = PathCollectionParser.formatTimes(nodeTimes);
        if (fromIndex < 0) {
            issues.add(pp + ".from 必须命中目标片段 " + clipId + " 的 position 节点时间（可用: "
                    + available + "，实际: " + from + "）");
        }
        if (toIndex < 0) {
            issues.add(pp + ".to 必须命中目标片段 " + clipId + " 的 position 节点时间（可用: "
                    + available + "，实际: " + to + "）");
        }
        if (fromIndex < 0 || toIndex < 0) return;
        if (toIndex != fromIndex + 1) {
            String next = fromIndex + 1 < nodeTimes.length
                    ? String.valueOf(nodeTimes[fromIndex + 1]) : "（无）";
            issues.add(pp + ".to 端点必须与 from 相邻（from=" + nodeTimes[fromIndex]
                    + " 的下一个 position 节点时间 = " + next + "，实际 to=" + to
                    + "；节点时间 " + available + "）");
            return;
        }
        String pairKey = clipId + "|" + nodeTimes[fromIndex] + "|" + nodeTimes[toIndex];
        String firstHost = endpointPairs.putIfAbsent(pairKey, pp);
        if (firstHost != null) {
            issues.add(pp + ".from 同一片段的端点对重复（clip=" + clipId + ", from="
                    + nodeTimes[fromIndex] + ", to=" + nodeTimes[toIndex] + "，先出现于 " + firstHost
                    + "）；端点对必须唯一（区间部分重叠合法）");
        }
    }

    /** 片段 id → 片段 JSON（内联 + 顶层 {@code clips[]}；重复 id 保留第一个，由 {@code checkClipIdentities} 报）。 */
    private static void collectClipObjects(JsonObject root, Map<String, JsonObject> clips) {
        JsonElement timelineElement = root.get("timeline");
        if (timelineElement != null && timelineElement.isJsonObject()) {
            JsonElement tracksElement = timelineElement.getAsJsonObject().get("tracks");
            if (tracksElement != null && tracksElement.isJsonArray()) {
                for (JsonElement trackElement : tracksElement.getAsJsonArray()) {
                    if (!trackElement.isJsonObject()) continue;
                    JsonElement clipsElement = trackElement.getAsJsonObject().get("clips");
                    if (clipsElement == null || !clipsElement.isJsonArray()) continue;
                    for (JsonElement clipElement : clipsElement.getAsJsonArray()) {
                        registerClipObject(clipElement, clips);
                    }
                }
            }
        }
        JsonElement standalone = root.get("clips");
        if (standalone != null && standalone.isJsonArray()) {
            for (JsonElement clipElement : standalone.getAsJsonArray()) {
                registerClipObject(clipElement, clips);
            }
        }
    }

    private static void registerClipObject(JsonElement clipElement, Map<String, JsonObject> clips) {
        if (clipElement == null || !clipElement.isJsonObject()) return;
        JsonObject clip = clipElement.getAsJsonObject();
        JsonElement idElement = clip.get("id");
        if (idElement == null || !isString(idElement) || idElement.getAsString().isEmpty()) return;
        clips.putIfAbsent(idElement.getAsString(), clip);
    }

    /** 片段是否为新格式（有 {@code objects} 且非空）。 */
    private static boolean hasObjects(JsonObject clip) {
        JsonElement objects = clip.get("objects");
        return objects != null && objects.isJsonObject() && objects.getAsJsonObject().size() > 0;
    }

    /** 读一个路径端点时间（缺失 / 非数字 / 非有限 → issue，返回 {@code null}）。 */
    private static Float readPathTime(JsonElement element, String path, List<String> issues) {
        if (element == null) {
            issues.add(path + " 缺失（需要有限数字）");
            return null;
        }
        if (!isNumber(element)) {
            issues.add(path + " 需要数字，实际: " + element);
            return null;
        }
        float value = element.getAsFloat();
        if (!Float.isFinite(value)) {
            issues.add(path + " 必须是有限数字（NaN / Infinity 不接受）");
            return null;
        }
        return value;
    }

    /** 规则 id：存在 / 字符串 / 格式 / 唯一。 */
    private static void checkRuleIdV2(JsonObject rule, String rp, Set<String> ruleIds,
                                      List<String> issues) {
        JsonElement idElement = rule.get("id");
        if (idElement == null) {
            issues.add(rp + ".id 缺失（规则 id 必填且脚本内唯一）");
            return;
        }
        if (!isString(idElement) || idElement.getAsString().isEmpty()) {
            issues.add(rp + ".id 需要非空字符串（规则 id）");
            return;
        }
        String id = idElement.getAsString();
        if (!RuleCollectionParser.isValidId(id)) {
            issues.add(rp + ".id 不合法: " + id + "（需匹配 " + RuleCollectionParser.ID_PATTERN + "）");
            return;
        }
        if (!ruleIds.add(id)) {
            issues.add(rp + ".id 重复的规则 id: " + id);
        }
    }

    /** 绑定 id（第一遍只收集与查重；结构问题在第二遍按声明顺序报）。 */
    private static void collectBindingId(JsonObject binding, String bp, Set<String> bindingIds,
                                         List<String> issues) {
        JsonElement idElement = binding.get("id");
        if (idElement == null) {
            issues.add(bp + ".id 缺失（绑定 id 必填且脚本内唯一）");
            return;
        }
        if (!isString(idElement) || idElement.getAsString().isEmpty()) {
            issues.add(bp + ".id 需要非空字符串（绑定 id）");
            return;
        }
        String id = idElement.getAsString();
        if (!RuleCollectionParser.isValidId(id)) {
            issues.add(bp + ".id 不合法: " + id + "（需匹配 " + RuleCollectionParser.ID_PATTERN + "）");
            return;
        }
        if (!bindingIds.add(id)) {
            issues.add(bp + ".id 重复的绑定 id: " + id);
        }
    }

    /** {@code policy}：键白名单 + 各字段取值（缺省值语义与 v1 {@code clip.rules} 同名同值）。 */
    private static void checkRulePolicy(JsonElement policyElement, String pp, Set<String> clipIds,
                                        List<String> issues) {
        if (!policyElement.isJsonObject()) {
            issues.add(pp + " 需要对象（策略字段: once / cooldown / mode / dwell / timeout / on_timeout）");
            return;
        }
        JsonObject policy = policyElement.getAsJsonObject();
        rejectKeys(policy, pp, issues, "once", "cooldown", "mode", "dwell", "timeout", "on_timeout");

        JsonElement once = policy.get("once");
        if (once != null && (!once.isJsonPrimitive() || !once.getAsJsonPrimitive().isBoolean())) {
            issues.add(pp + ".once 需要布尔值（true = 只触发一次；缺省 true）");
        }
        checkOptionalNonNegative(policy, pp, "cooldown", issues);
        checkOptionalNonNegative(policy, pp, "dwell", issues);

        JsonElement mode = policy.get("mode");
        if (mode != null && (!isString(mode) || !RuleDefinition.MODES.contains(mode.getAsString()))) {
            issues.add(pp + ".mode 需要 enter / dwell（缺省 enter），实际: " + mode);
        }
        JsonElement timeout = policy.get("timeout");
        if (timeout != null) {
            if (!isNumber(timeout)) {
                issues.add(pp + ".timeout 需要数字（秒，> 0；缺省 -1 = 无限等待）");
            } else {
                double value = timeout.getAsDouble();
                if (value != RuleDefinition.TIMEOUT_NONE && (!Double.isFinite(value) || !(value > 0.0))) {
                    issues.add(pp + ".timeout 需要 > 0 的有限数字或 -1（无限等待），实际: " + timeout);
                }
            }
        }
        JsonElement onTimeout = policy.get("on_timeout");
        if (onTimeout != null) {
            if (!onTimeout.isJsonArray()) {
                issues.add(pp + ".on_timeout 需要数组（元素 = 动作对象）");
            } else {
                checkActionArray(onTimeout, pp + ".on_timeout", false, issues, clipIds);
            }
        }
    }

    /** 可选非负数字字段（缺失跳过）。 */
    private static void checkOptionalNonNegative(JsonObject obj, String path, String key,
                                                 List<String> issues) {
        JsonElement element = obj.get(key);
        if (element == null) return;
        if (!isNumber(element) || !Float.isFinite(element.getAsFloat()) || element.getAsFloat() < 0f) {
            issues.add(path + "." + key + " 需要 >= 0 的有限数字（秒），实际: " + element);
        }
    }

    /** 单条绑定：键白名单、rule / clip 引用、gate 数值与范围、control、schedule / exit 结构。 */
    private static void checkRuleBinding(JsonObject binding, String bp, Set<String> ruleIds,
                                         Set<String> bindingIds, Set<String> clipIds,
                                         Map<String, Float> clipDurations, List<String> issues) {
        rejectKeys(binding, bp, issues, "id", "rule", "clip", "gate", "control", "schedule", "exit");

        JsonElement rule = binding.get("rule");
        if (rule == null) {
            issues.add(bp + ".rule 缺失（必须命中 rules[].id）");
        } else if (!isString(rule) || rule.getAsString().isEmpty()) {
            issues.add(bp + ".rule 需要非空字符串（规则 id）");
        } else if (!ruleIds.contains(rule.getAsString())) {
            issues.add(bp + ".rule 未知规则引用: " + rule.getAsString());
        }

        String clipId = null;
        JsonElement clip = binding.get("clip");
        if (clip == null) {
            issues.add(bp + ".clip 缺失（必须命中片段 id）");
        } else if (!isString(clip) || clip.getAsString().isEmpty()) {
            issues.add(bp + ".clip 需要非空字符串（片段 id）");
        } else {
            clipId = clip.getAsString();
            if (!clipIds.contains(clipId)) {
                issues.add(bp + ".clip 未知片段引用: " + clipId);
                clipId = null;
            }
        }

        checkBindingGate(binding, bp, clipId, clipDurations, issues);

        JsonElement control = binding.get("control");
        if (control != null && (!isString(control) || !RuleControl.SCRIPT_NAMES.contains(control.getAsString()))) {
            issues.add(bp + ".control 需要 observe / hold（缺省 observe），实际: " + control);
        }

        JsonElement schedule = binding.get("schedule");
        if (schedule != null) {
            if (!schedule.isJsonObject()) {
                issues.add(bp + ".schedule 需要对象（释放条件: clip_start / clip_complete / local_time / delay / fact）");
            } else {
                checkScheduleStructure(schedule.getAsJsonObject(), bp + ".schedule",
                        bindingIds, clipIds, issues);
            }
        }

        JsonElement exit = binding.get("exit");
        if (exit != null) {
            if (!exit.isJsonObject()) {
                issues.add(bp + ".exit 需要对象（出口策略）");
            } else {
                checkExitStructure(exit.getAsJsonObject(), bp + ".exit", clipIds, issues);
            }
        }
    }

    /**
     * {@code control=hold} 且无超时（{@code policy.timeout} 缺省 / {@code -1}）时的释放可达性：
     * 条件树等待「另一个片段」的 {@code clip_fact}，而该片段起点晚于目标片段结束 →
     * 冻结的内容位置不会再推进到那里。警告级（不阻止加载，运行期另有同口径限频诊断）。
     */
    private static void checkHoldReachability(JsonObject binding, String bp,
                                              Map<String, JsonObject> rules,
                                              Map<String, JsonObject> clips,
                                              List<String> issues) {
        JsonElement control = binding.get("control");
        if (control == null || !isString(control) || !"hold".equals(control.getAsString())) return;

        JsonElement ruleRef = binding.get("rule");
        if (ruleRef == null || !isString(ruleRef)) return;
        JsonObject rule = rules.get(ruleRef.getAsString());
        if (rule == null) return;
        if (hasFiniteTimeout(rule.get("policy"))) return;      // 超时 = 可达释放路径

        JsonElement clipRef = binding.get("clip");
        if (clipRef == null || !isString(clipRef)) return;
        String targetId = clipRef.getAsString();
        JsonObject target = clips.get(targetId);
        if (target == null) return;
        double targetStart = numberField(target, "start_time");
        double targetDuration = numberField(target, "duration");
        if (!Double.isFinite(targetStart) || !Double.isFinite(targetDuration) || targetDuration < 0.0) {
            return;
        }
        double targetEnd = targetStart + targetDuration;

        String waiting = firstClipFactBeyond(rule.get("condition"), targetId, clips, targetEnd, 1);
        if (waiting == null) return;
        String bindingId = bindingIdOf(binding);
        issues.add(WARNING_PREFIX + bp + " 绑定 " + bindingId + " 声明 control=hold，但条件等待片段 "
                + waiting + " 开始，而目标片段结束后内容位置不再推进——该持有不可达");
    }

    /** {@code rules[]} 的 id → 规则 JSON（重复 id 保留第一个，重复本身另报）。 */
    private static Map<String, JsonObject> collectRuleObjects(JsonObject root) {
        Map<String, JsonObject> rules = new LinkedHashMap<>();
        JsonElement rulesElement = root.get("rules");
        if (rulesElement == null || !rulesElement.isJsonArray()) return rules;
        for (JsonElement element : rulesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            JsonElement id = rule.get("id");
            if (id == null || !isString(id) || id.getAsString().isEmpty()) continue;
            rules.putIfAbsent(id.getAsString(), rule);
        }
        return rules;
    }

    /** {@code policy.timeout} 是否为正的有限值（{@code -1} / 缺省 = 无限等待）。 */
    private static boolean hasFiniteTimeout(JsonElement policy) {
        if (policy == null || !policy.isJsonObject()) return false;
        JsonElement timeout = policy.getAsJsonObject().get("timeout");
        return timeout != null && isNumber(timeout) && timeout.getAsDouble() > 0.0;
    }

    /** 片段 JSON 的数值字段（缺失 / 非数字 → {@code NaN}）。 */
    private static double numberField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && isNumber(element) ? element.getAsDouble() : Double.NaN;
    }

    /** 绑定的 id 原文（缺失 / 非字符串 → {@code "<unnamed>"}）。 */
    private static String bindingIdOf(JsonObject binding) {
        JsonElement id = binding.get("id");
        return id != null && isString(id) && !id.getAsString().isEmpty()
                ? id.getAsString() : "<unnamed>";
    }

    /**
     * 条件树里第一个「等待目标片段以外、起点晚于 {@code targetEnd} 的片段」的 {@code clip_fact}
     * 片段 id；无 → {@code null}。只读递归（{@code all_of} / {@code any} 的 {@code list} 与
     * 嵌套组合器的 {@code conditions}）。
     */
    private static String firstClipFactBeyond(JsonElement node, String targetId,
                                              Map<String, JsonObject> clips, double targetEnd, int depth) {
        if (node == null || !node.isJsonObject() || depth > 16) return null;
        JsonObject object = node.getAsJsonObject();
        JsonElement typeElement = object.get("type");
        if (typeElement == null || !isString(typeElement)) return null;
        String type = typeElement.getAsString();

        JsonObject data = object.has("conditions") && object.get("conditions").isJsonObject()
                ? object.getAsJsonObject("conditions") : object;
        if ("clip_fact".equals(type)) {
            JsonElement clip = data.get("clip");
            if (clip == null || !isString(clip)) return null;
            String clipId = clip.getAsString();
            if (clipId.isEmpty() || clipId.equals(targetId)) return null;
            JsonObject referenced = clips.get(clipId);
            if (referenced == null) return null;
            double start = numberField(referenced, "start_time");
            return Double.isFinite(start) && start > targetEnd ? clipId : null;
        }
        if (!"all_of".equals(type) && !"any".equals(type)) return null;

        String hit = firstClipFactBeyondList(object.get("list"), targetId, clips, targetEnd, depth);
        if (hit != null) return hit;
        return data != object
                ? firstClipFactBeyondList(data.get("list"), targetId, clips, targetEnd, depth) : null;
    }

    /** 组合器子节点数组：逐元素递归（非数组 → 无命中）。 */
    private static String firstClipFactBeyondList(JsonElement list, String targetId,
                                                  Map<String, JsonObject> clips,
                                                  double targetEnd, int depth) {
        if (list == null || !list.isJsonArray()) return null;
        for (JsonElement element : list.getAsJsonArray()) {
            String hit = firstClipFactBeyond(element, targetId, clips, targetEnd, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** {@code gate}：必填对象、{@code at} 必填有限数值、有限片段 {@code 0 <= at <= duration}。 */
    private static void checkBindingGate(JsonObject binding, String bp, String clipId,
                                         Map<String, Float> durations, List<String> issues) {
        if (!binding.has("gate")) {
            issues.add(bp + ".gate 缺失（时间门必填：{\"at\": <目标片段执行局部秒>}）");
            return;
        }
        JsonElement gateElement = binding.get("gate");
        if (!gateElement.isJsonObject()) {
            issues.add(bp + ".gate 需要对象 {\"at\": <秒>}");
            return;
        }
        JsonObject gate = gateElement.getAsJsonObject();
        rejectKeys(gate, bp + ".gate", issues, "at");
        JsonElement atElement = gate.get("at");
        if (atElement == null || !isNumber(atElement)) {
            issues.add(bp + ".gate.at 缺失或不是数值（执行局部秒）");
            return;
        }
        double at = atElement.getAsDouble();
        if (!Double.isFinite(at) || at < 0.0) {
            issues.add(bp + ".gate.at 需要 >= 0 的有限数字，实际: " + atElement);
            return;
        }
        if (clipId == null) return;
        Float duration = durations.get(clipId);
        if (duration == null || duration == 0f) return;
        if (duration > 0f && at > duration) {
            issues.add(bp + ".gate.at 越界: " + at + "（片段 " + clipId + " 时长 " + duration
                    + "，允许范围 0 ~ " + duration + "）");
        }
    }

    /** {@code schedule}：{@code on} 枚举 + 各形态参数 + 引用（不构造 {@link Clip}）。 */
    private static void checkScheduleStructure(JsonObject schedule, String sp,
                                               Set<String> bindingIds, Set<String> clipIds,
                                               List<String> issues) {
        JsonElement on = schedule.get("on");
        if (on == null || !isString(on)) {
            issues.add(sp + ".on 缺失或不是字符串（释放条件: clip_start / clip_complete / local_time / delay / fact）");
            return;
        }
        switch (on.getAsString()) {
            case "clip_start", "clip_complete" -> {
                checkClipRefField(schedule, sp, clipIds, issues);
                rejectKeys(schedule, sp, issues, "on", "clip");
            }
            case "local_time" -> {
                checkClipRefField(schedule, sp, clipIds, issues);
                JsonElement at = schedule.get("at");
                if (at == null || !isNumber(at)) {
                    issues.add(sp + ".at 缺失或不是数值（目标片段执行局部秒，>= 0）");
                } else if (!Double.isFinite(at.getAsDouble()) || at.getAsDouble() < 0.0) {
                    issues.add(sp + ".at 需要 >= 0 的有限数字，实际: " + at);
                }
                rejectKeys(schedule, sp, issues, "on", "clip", "at");
            }
            case "delay" -> {
                JsonElement seconds = schedule.get("seconds");
                if (seconds == null || !isNumber(seconds)) {
                    issues.add(sp + ".seconds 缺失或不是数值（真实秒数，>= 0）");
                } else if (!Double.isFinite(seconds.getAsDouble()) || seconds.getAsDouble() < 0.0) {
                    issues.add(sp + ".seconds 需要 >= 0 的有限数字，实际: " + seconds);
                }
                rejectKeys(schedule, sp, issues, "on", "seconds");
            }
            case "fact" -> {
                JsonElement binding = schedule.get("binding");
                if (binding == null || !isString(binding) || binding.getAsString().isEmpty()) {
                    issues.add(sp + ".binding 缺失或不是非空字符串（来源绑定 id）");
                } else if (!bindingIds.contains(binding.getAsString())) {
                    issues.add(sp + ".binding 未知绑定引用: " + binding.getAsString());
                }
                checkFactName(schedule, sp, issues, "succeeded", "action_completed");
                rejectKeys(schedule, sp, issues, "on", "binding", "fact");
            }
            default -> issues.add(sp + ".on 未知释放条件: " + on.getAsString()
                    + "（可选: clip_start / clip_complete / local_time / delay / fact）");
        }
    }

    /** {@code clip} 引用字段：非空字符串且可解析。 */
    private static void checkClipRefField(JsonObject obj, String path, Set<String> clipIds,
                                          List<String> issues) {
        JsonElement clip = obj.get("clip");
        if (clip == null || !isString(clip) || clip.getAsString().isEmpty()) {
            issues.add(path + ".clip 缺失或不是非空字符串（目标片段 id）");
        } else if (!clipIds.contains(clip.getAsString())) {
            issues.add(path + ".clip 未知片段引用: " + clip.getAsString());
        }
    }

    /**
     * {@code exit} 结构校验（与 {@code ClipExitPolicyParser} 同口径，但不构造 {@link Clip}）：
     * {@code on_complete} 必填枚举；{@code goto} 必须有可解析 {@code target}，{@code entry_time} 可选
     * 有限 {@code >= 0}；其余类型禁止 {@code target} / {@code entry_time}；未知键报错。
     */
    private static void checkExitStructure(JsonObject exit, String ep, Set<String> clipIds,
                                           List<String> issues) {
        rejectKeys(exit, ep, issues,
                ClipExitPolicyParser.KEYS.toArray(new String[0]));
        JsonElement kindElement = exit.get("on_complete");
        if (kindElement == null || !isString(kindElement)) {
            issues.add(ep + ".on_complete 缺失或不是字符串（continue / goto / return / end_execution / end_script）");
            return;
        }
        ClipExitKind kind = ClipExitPolicyParser.kindOf(kindElement.getAsString());
        if (kind == null) {
            issues.add(ep + ".on_complete 未知出口类型: " + kindElement.getAsString()
                    + "（可选: continue / goto / return / end_execution / end_script）");
            return;
        }
        if (kind == ClipExitKind.GOTO) {
            JsonElement target = exit.get("target");
            if (target == null || !isString(target) || target.getAsString().isEmpty()) {
                issues.add(ep + ".target 缺失（goto 必须有目标片段 id）");
            } else if (!clipIds.contains(target.getAsString())) {
                issues.add(ep + ".target 未知片段引用: " + target.getAsString());
            }
            JsonElement entry = exit.get("entry_time");
            if (entry != null && (!isNumber(entry) || !Double.isFinite(entry.getAsDouble())
                    || entry.getAsDouble() < 0.0)) {
                issues.add(ep + ".entry_time 需要 >= 0 的有限数字（入口局部秒），实际: " + entry);
            }
            return;
        }
        if (exit.has("target")) {
            issues.add(ep + ".target 不允许（只有 goto 需要 target）");
        }
        if (exit.has("entry_time")) {
            issues.add(ep + ".entry_time 不允许（只有 goto 需要 entry_time）");
        }
    }

    /** 键白名单（未知键报 issue，不抛）。 */
    private static void rejectKeys(JsonObject obj, String path, List<String> issues, String... allowed) {
        for (String key : obj.keySet()) {
            boolean known = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                issues.add(path + "." + key + " 未知键（可用: " + String.join(" / ", allowed) + "）");
            }
        }
    }

    /**
     * 字段白名单（未知键报「未知字段」，不抛）。白名单是 TreeSet → 文案顺序稳定。
     *
     * @param path 路径前缀（{@code meta} / {@code timeline}）
     */
    private static void rejectUnknownFields(JsonObject obj, String path, Set<String> allowed,
                                            List<String> issues) {
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                issues.add(path + "." + key + " 未知字段（可用: " + String.join(" / ", allowed) + "）");
            }
        }
    }

    /** 条件树里的事实引用上下文（{@code null} = 旧 v1 口径，事实类型按未知判定源报错）。 */
    private record FactRefs(Set<String> bindingIds, Set<String> clipIds) {}

    /**
     * 脚本级规则定义的条件树：与 v1 同构，额外允许 {@code clip_fact} / {@code rule_fact}
     * （字段类型 + 枚举 + 引用可解析）；其它类型复用 {@link #checkRuleConditionNode0}。
     */
    private static void checkRuleConditionV2(JsonObject condition, String cp, List<String> issues,
                                             Set<String> bindingIds, Set<String> clipIds) {
        JsonElement type = condition.get("type");
        if (type == null || !isString(type)) {
            issues.add(cp + ".type 缺失或不是字符串（判定源类型；可用: "
                    + String.join(" / ", ClientConditions.V1_TYPES) + " / all_of / any / "
                    + String.join(" / ", ClientConditions.FACT_TYPES) + "）");
            return;
        }
        checkRuleConditionNodeV2(condition, cp, type.getAsString(), 1, issues, bindingIds, clipIds);
    }

    /** 脚本级规则的条件节点入口（事实类型放行；与 v1 条件节点同构）。 */
    private static void checkRuleConditionNodeV2(JsonObject c, String cp, String type, int depth,
                                                 List<String> issues, Set<String> bindingIds,
                                                 Set<String> clipIds) {
        checkRuleConditionNode0(c, cp, type, depth, issues, new FactRefs(bindingIds, clipIds));
    }

    /** 单个条件节点：组合器递归下钻（层数用尽即报），叶子按类型做字段校验。 */
    

    /** 单个条件节点（旧 v1 与脚本级规则共用；{@code facts != null} 时放行事实类型）。 */
    private static void checkRuleConditionNode0(JsonObject c, String cp, String type, int depth,
                                                List<String> issues, FactRefs facts) {
        if ("all_of".equals(type) || "any".equals(type)) {
            checkRuleCombination0(c, cp, type, depth, issues, facts);
            return;
        }
        if (facts != null && ClientConditions.FACT_TYPES.contains(type)) {
            checkRuleFactNode(c, cp, type, issues, facts);
            return;
        }
        if (!ClientConditions.V1_TYPES.contains(type)) {
            issues.add(cp + ".type " + ruleSourceSupport(type) + ": " + type
                    + "（v1 判定源: " + v1SourceLabel() + " + all_of/any）");
            return;
        }
        switch (type) {
            case "time" -> checkRuleTime(c, cp, issues);
            case "camera_pos" -> checkRuleCameraPos(c, cp, issues);
            case "camera_facing" -> checkRuleCameraFacing(c, cp, issues);
            case "camera_fov" -> checkRuleCameraFov(c, cp, issues);
            default -> {
                // location / facing / biome / dimension：与触发器同名词汇，复用同一份 schema 驱动校验
                validateConditionsBySchema(c, cp, type, issues);
                if ("facing".equals(type)) validateFacingConditions(c, cp, issues);
            }
        }
    }

    /**
     * 事实叶子：{@code clip_fact} = {@code clip}（片段 id）+ {@code fact}（started / completed）；
     * {@code rule_fact} = {@code binding}（绑定 id）+ {@code fact}（succeeded / action_completed）。
     * 字段类型 / 枚举 / 引用可解析性逐项报 issue（文案带路径）。
     */
    private static void checkRuleFactNode(JsonObject c, String cp, String type, List<String> issues,
                                          FactRefs refs) {
        if ("clip_fact".equals(type)) {
            JsonElement clip = c.get("clip");
            if (clip == null || !isString(clip) || clip.getAsString().isEmpty()) {
                issues.add(cp + ".clip 缺失或不是非空字符串（片段 id）");
            } else if (!refs.clipIds().contains(clip.getAsString())) {
                issues.add(cp + ".clip 未知片段引用: " + clip.getAsString());
            }
            checkFactName(c, cp, issues, "started", "completed");
            return;
        }
        JsonElement binding = c.get("binding");
        if (binding == null || !isString(binding) || binding.getAsString().isEmpty()) {
            issues.add(cp + ".binding 缺失或不是非空字符串（绑定 id）");
        } else if (!refs.bindingIds().contains(binding.getAsString())) {
            issues.add(cp + ".binding 未知绑定引用: " + binding.getAsString());
        }
        checkFactName(c, cp, issues, "succeeded", "action_completed");
    }

    /** {@code fact} 字段：必填字符串且在允许枚举内。 */
    private static void checkFactName(JsonObject c, String cp, List<String> issues, String... allowed) {
        JsonElement fact = c.get("fact");
        if (fact == null || !isString(fact)) {
            issues.add(cp + ".fact 缺失或不是字符串（可选: " + String.join(" / ", allowed) + "）");
            return;
        }
        for (String value : allowed) {
            if (value.equals(fact.getAsString())) return;
        }
        issues.add(cp + ".fact 未知事实: " + fact.getAsString()
                + "（可选: " + String.join(" / ", allowed) + "）");
    }

    /** 组合器（旧 v1 与脚本级规则共用；{@code facts != null} 时子类型放行事实叶子）。 */
    private static void checkRuleCombination0(JsonObject c, String cp, String combinator, int depth,
                                              List<String> issues, FactRefs facts) {
        if (!c.has("list") || !c.get("list").isJsonArray()) {
            issues.add(cp + ".list 缺失或不是数组（" + combinator
                    + " 需要 \"list\": [ { \"type\": ..., \"conditions\": { ... } }, ... ]）");
            return;
        }
        JsonArray list = c.getAsJsonArray("list");
        if (list.isEmpty()) {
            issues.add(cp + ".list 不能为空");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            JsonElement e = list.get(i);
            String sp = cp + ".list[" + i + "]";
            if (!e.isJsonObject()) {
                issues.add(sp + " 不是对象（应为 { type, conditions }）");
                continue;
            }
            JsonObject sub = e.getAsJsonObject();
            JsonElement subTypeEl = sub.get("type");
            if (subTypeEl == null || !isString(subTypeEl) || subTypeEl.getAsString().isEmpty()) {
                issues.add(sp + ".type 缺失或不是非空字符串");
                continue;
            }
            String subType = subTypeEl.getAsString();
            boolean badConditions = sub.has("conditions") && !sub.get("conditions").isJsonObject();
            if (badConditions) {
                issues.add(sp + ".conditions 必须是对象");
                continue;
            }
            JsonObject data = sub.has("conditions") ? sub.getAsJsonObject("conditions") : null;
            if ("all_of".equals(subType) || "any".equals(subType)) {
                if (depth + 1 > ClientConditions.MAX_COMBINATION_DEPTH) {
                    issues.add(sp + " 嵌套超过 " + ClientConditions.MAX_COMBINATION_DEPTH
                            + " 层（组合器最大深度，含顶层）: " + subType);
                    continue;
                }
                checkRuleCombination0(data != null ? data : new JsonObject(), sp + ".conditions", subType,
                        depth + 1, issues, facts);
                continue;
            }
            if (facts != null && ClientConditions.FACT_TYPES.contains(subType)) {
                if (data == null) {
                    issues.add(sp + ".conditions 缺失（事实 " + subType + " 的数据对象）");
                    continue;
                }
                checkRuleFactNode(data, sp + ".conditions", subType, issues, facts);
                continue;
            }
            if (!ClientConditions.V1_TYPES.contains(subType)) {
                issues.add(sp + ".type " + ruleSourceSupport(subType) + ": " + subType
                        + "（v1 判定源: " + v1SourceLabel() + " + all_of/any）");
                continue;
            }
            if (data == null) {
                issues.add(sp + ".conditions 缺失（判定源 " + subType + " 的数据对象）");
                continue;
            }
            checkRuleConditionNode0(data, sp + ".conditions", subType, depth, issues, facts);
        }
    }

    /** {@code time}：{@code at}（到点）或 {@code from} / {@code to}（闭区间）至少一个，两者互斥。 */
    private static void checkRuleTime(JsonObject c, String cp, List<String> issues) {
        boolean at = c.has("at");
        boolean from = c.has("from");
        boolean to = c.has("to");
        checkRuleNumbers(c, cp, issues, "at", "from", "to");
        if (!at && !from && !to) {
            issues.add(cp + " 缺少时间参数（需要 at 或 from/to 至少一个，单位秒；相对片段本地时钟）");
        } else if (at && (from || to)) {
            issues.add(cp + " at 与 from/to 互斥（at = 到点，from/to = 区间，二者只写一种）");
        }
    }

    /** {@code camera_pos}：方体（corner1+corner2）或球（position+radius）至少一组。 */
    private static void checkRuleCameraPos(JsonObject c, String cp, List<String> issues) {
        boolean box = c.has("corner1") || c.has("corner2");
        boolean sphere = c.has("position");
        if (!box && !sphere) {
            issues.add(cp + " 缺少区域参数（需要 position+radius 或 corner1+corner2；坐标为相机世界坐标）");
            return;
        }
        for (String key : new String[]{"position", "corner1", "corner2"}) {
            if (c.has(key) && !c.get(key).isJsonObject()) {
                issues.add(cp + "." + key + " 需要对象（形如 { \"x\": ..., \"y\": ..., \"z\": ... }）");
            }
        }
        checkRuleNumbers(c, cp, issues, "radius");
    }

    /** {@code camera_facing}：与 facing 同字段（yaw1/pitch1/yaw2/pitch2），作用对象换成相机朝向。 */
    private static void checkRuleCameraFacing(JsonObject c, String cp, List<String> issues) {
        validateFacingConditions(c, cp, issues);
    }

    /** {@code camera_fov}：{@code from} / {@code to} 至少一个（闭区间，度）。 */
    private static void checkRuleCameraFov(JsonObject c, String cp, List<String> issues) {
        boolean from = c.has("from");
        boolean to = c.has("to");
        checkRuleNumbers(c, cp, issues, "from", "to");
        if (!from && !to) {
            issues.add(cp + " 缺少 FOV 参数（需要 from / to 至少一个，单位度）");
        }
    }

    /** 指定字段：存在则必须是有限数字（缺省值语义由求值侧定）。 */
    private static void checkRuleNumbers(JsonObject c, String cp, List<String> issues, String... keys) {
        for (String key : keys) {
            JsonElement e = c.get(key);
            if (e != null && (!isNumber(e) || !Float.isFinite(e.getAsFloat()))) {
                issues.add(cp + "." + key + " 需要有限数字，实际: " + e);
            }
        }
    }

    /** v1 判定源的可读清单（报错文案用）。 */
    private static String v1SourceLabel() {
        StringBuilder sb = new StringBuilder();
        for (String type : ClientConditions.V1_TYPES) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(type);
        }
        return sb.toString();
    }

    /** 报错文案：已注册的触发器 / 前置条件（v1 未覆盖）与完全未知 type 分开表述。 */
    private static String ruleSourceSupport(String type) {
        if (TriggerRegistry.get(type) != null || PrerequisiteRegistry.has(type)) {
            return "是 v1 不支持的判定源（触发器 / 前置条件类判定源留 v2）";
        }
        return "未知判定源";
    }

    /** 动作数组：{@code do} 必填非空、{@code on_timeout} 可省；元素 = 对象且 {@code type} 为字符串。 */
    

    /**
     * 动作数组：{@code required} 时空数组也报；元素须为对象且带字符串 {@code type}
     * （白名单与参数结构见 {@link #checkActionElement}）。
     */
    private static void checkActionArray(JsonElement e, String ap, boolean required, List<String> issues) {
        checkActionArray(e, ap, required, issues, null);
    }

    /**
     * 动作数组（脚本级规则用）：{@code clipIds != null} 时元素走新形态校验
     * （{@code goto_clip} 用片段 id 寻址，见 {@link #checkActionElementNew}）。
     */
    private static void checkActionArray(JsonElement e, String ap, boolean required, List<String> issues,
                                         Set<String> clipIds) {
        if (!e.isJsonArray()) {
            issues.add(ap + " 需要数组（元素 = 动作对象）");
            return;
        }
        JsonArray arr = e.getAsJsonArray();
        if (required && arr.isEmpty()) {
            issues.add(ap + " 为空（规则至少一个动作）");
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            String ip = ap + "[" + i + "]";
            if (!el.isJsonObject()) {
                issues.add(ip + " 需要对象（动作元素）");
                continue;
            }
            if (clipIds == null) {
                checkActionElement(el.getAsJsonObject(), ip, issues);
            } else {
                checkActionElementNew(el.getAsJsonObject(), ip, clipIds, issues);
            }
        }
    }

    /**
     * 新规则动作元素：{@code goto_clip} 走新形态（{@code clip} + {@code entry_time}；拒绝旧
     * {@code track} / {@code at} 寻址），其余类型复用 v1 动作校验（{@link #checkActionElement}）。
     */
    private static void checkActionElementNew(JsonObject action, String ip, Set<String> clipIds,
                                              List<String> issues) {
        JsonElement typeEl = action.get("type");
        if (typeEl == null || !isString(typeEl)) {
            issues.add(ip + ".type 缺失或不是字符串（动作元素需要 type；v1 动作: "
                    + RuleActions.typesLabel() + "）");
            return;
        }
        if (!"goto_clip".equals(typeEl.getAsString())) {
            checkActionElement(action, ip, issues);
            return;
        }
        JsonElement clip = action.get("clip");
        if (clip == null || !isString(clip) || clip.getAsString().isEmpty()) {
            issues.add(ip + ".clip 缺失或不是非空字符串（新形态 goto_clip 用片段 id 寻址）");
        } else if (!clipIds.contains(clip.getAsString())) {
            issues.add(ip + ".clip 未知片段引用: " + clip.getAsString());
        }
        JsonElement entry = action.get("entry_time");
        if (entry != null && (!isNumber(entry) || !Double.isFinite(entry.getAsDouble())
                || entry.getAsDouble() < 0.0)) {
            issues.add(ip + ".entry_time 需要 >= 0 的有限数字（入口局部秒），实际: " + entry);
        }
        for (String key : action.keySet()) {
            if ("type".equals(key) || "clip".equals(key) || "entry_time".equals(key)) continue;
            if ("track".equals(key) || "at".equals(key)) {
                issues.add(ip + "." + key + " 属旧字段，用 clip / entry_time");
            } else {
                issues.add(ip + "." + key + " 未知键（新规则 goto_clip 可用: clip / entry_time）");
            }
        }
    }

    /**
     * 单个动作元素：{@code type} 白名单（v1 九种，见 {@link RuleActions#ACTION_TYPES_V1}）+ 各动作参数结构；
     * 与 {@code RuleActions} 的运行期解析同一口径（校验拦下的写法运行期也会跳过——不静默无效）。
     * {@code hold} 是销项动作：语义 = {@code until} 无 timeout（无限等待），不单独实现。
     */
    private static void checkActionElement(JsonObject action, String ip, List<String> issues) {
        JsonElement typeEl = action.get("type");
        if (typeEl == null || !isString(typeEl)) {
            issues.add(ip + ".type 缺失或不是字符串（动作元素需要 type；v1 动作: "
                    + RuleActions.typesLabel() + "）");
            return;
        }
        String type = typeEl.getAsString();
        switch (type) {
            case "camera_set" -> checkModifierBody(action, ip, issues, "type");
            case "overlay_set" -> checkActionOverlaySet(action, ip, issues);
            case "adjust_set" -> checkActionAdjustSet(action, ip, issues);
            case "goto_clip" -> checkActionGotoClip(action, ip, issues);
            case "play_sound" -> checkActionPlaySound(action, ip, issues);
            case "emit_command" -> checkActionEmitCommand(action, ip, issues);
            case "pause", "resume", "exit" -> rejectActionKeys(action, ip, issues);
            case RuleActions.ACTION_HOLD -> issues.add(ip + " hold 动作未实现（销项）：无限等待用 until 无 timeout 表达"
                    + "——{\"until\": {<条件>}, \"do\": [...]}（缺省 timeout = 无限等待）");
            default -> issues.add(ip + ".type 未知动作: " + type + "（v1 动作: " + RuleActions.typesLabel() + "）");
        }
    }

    /**
     * {@code overlay_set}：{@code layer_type} 必填（v1 = {@code fade}；image / subtitle 留 v2）+
     * {@code opacity} 0~1（缺省 1）+ {@code color} 十六进制 + {@code z_index} 整数。
     */
    private static void checkActionOverlaySet(JsonObject action, String ip, List<String> issues) {
        if (!action.has("layer_type")) {
            issues.add(ip + ".layer_type 缺失（v1 支持: "
                    + String.join(" / ", RuleActions.OVERLAY_LAYER_TYPES_V1) + "）");
        } else {
            checkEnum(action, ip, "layer_type", issues,
                    RuleActions.OVERLAY_LAYER_TYPES_V1.toArray(new String[0]));
        }
        checkHexColor(action, ip, "color", issues);
        JsonElement opacity = action.get("opacity");
        if (opacity != null && (!isNumber(opacity) || !Float.isFinite(opacity.getAsFloat())
                || opacity.getAsFloat() < 0f || opacity.getAsFloat() > 1f)) {
            issues.add(ip + ".opacity 需要 0~1 的有限数字（缺省 1 = 全遮），实际: " + opacity);
        }
        JsonElement zIndex = action.get("z_index");
        if (zIndex != null && (!isNumber(zIndex) || !Double.isFinite(zIndex.getAsDouble())
                || zIndex.getAsDouble() != Math.rint(zIndex.getAsDouble()))) {
            issues.add(ip + ".z_index 需要整数（层级索引：数值越大越靠上；缺省 10），实际: " + zIndex);
        }
        rejectActionKeys(action, ip, issues, "layer_type", "opacity", "color", "z_index");
    }

    /**
     * {@code adjust_set}：参数名 ∈ {@link RuleActions#ADJUST_CHANNELS_V1}（与 ADJUST 轨关键帧字段同名），
     * 值为有限数字；至少一个通道（只写 type = 什么都不做）。
     */
    private static void checkActionAdjustSet(JsonObject action, String ip, List<String> issues) {
        String hint = String.join(" / ", RuleActions.ADJUST_CHANNELS_V1);
        int declared = 0;
        for (String key : action.keySet()) {
            if ("type".equals(key)) continue;
            declared++;
            if (!RuleActions.ADJUST_CHANNELS_V1.contains(key)) {
                issues.add(ip + "." + key + " 未知通道（v1 支持: " + hint + "）");
                continue;
            }
            JsonElement e = action.get(key);
            if (!isNumber(e) || !Float.isFinite(e.getAsFloat())) {
                issues.add(ip + "." + key + " 需要有限数字（缺省 0 = 不改变画面）");
            }
        }
        if (declared == 0) {
            issues.add(ip + " 未声明任何通道（至少一个: " + hint + "）");
        }
    }

    /**
     * {@code goto_clip}：{@code track}（轨道类型名）+ {@code at}（目标已播放时间秒 ≥ 0）时间寻址；
     * 不引入 clip id 字段。目标时间须落在该轨某个片段的窗内（运行期解析，越界 = 限频日志 + 不动）。
     */
    private static void checkActionGotoClip(JsonObject action, String ip, List<String> issues) {
        JsonElement track = action.get("track");
        if (track == null) {
            issues.add(ip + ".track 缺失（轨道类型名: " + trackTypesHint() + "）");
        } else if (!isString(track) || trackTypeOf(track.getAsString()) == null) {
            issues.add(ip + ".track 未知轨道类型: " + track + "（可选: " + trackTypesHint() + "）");
        }
        JsonElement at = action.get("at");
        if (at == null) {
            issues.add(ip + ".at 缺失（目标已播放时间，秒；须落在该轨某个片段的窗内）");
        } else if (!isNumber(at) || !Float.isFinite(at.getAsFloat()) || at.getAsFloat() < 0f) {
            issues.add(ip + ".at 需要 ≥ 0 的有限数字（秒），实际: " + at);
        }
        rejectActionKeys(action, ip, issues, "track", "at");
    }

    /** {@code play_sound}：{@code sound} 必填（非空字符串）+ {@code volume} / {@code pitch} 可选（有限数字）。 */
    private static void checkActionPlaySound(JsonObject action, String ip, List<String> issues) {
        JsonElement sound = action.get("sound");
        if (sound == null || !isString(sound) || sound.getAsString().isEmpty()) {
            issues.add(ip + ".sound 缺失或不是非空字符串（音效 id，如 minecraft:ambient.cave）");
        }
        checkActionNumber(action, ip, "volume", issues);
        checkActionNumber(action, ip, "pitch", issues);
        rejectActionKeys(action, ip, issues, "sound", "volume", "pitch");
    }

    /** {@code emit_command}：{@code command} 必填（非空字符串；多条用 {@code &&} 分隔）。 */
    private static void checkActionEmitCommand(JsonObject action, String ip, List<String> issues) {
        JsonElement command = action.get("command");
        if (command == null || !isString(command) || command.getAsString().isBlank()) {
            issues.add(ip + ".command 缺失或不是非空字符串（命令原文，不含前导 /；多条用 && 分隔）");
        }
        rejectActionKeys(action, ip, issues, "command");
    }

    /** 可选数字参数（缺失 = 跳过；非法 → issue）。 */
    private static void checkActionNumber(JsonObject action, String ip, String key, List<String> issues) {
        JsonElement e = action.get(key);
        if (e != null && (!isNumber(e) || !Float.isFinite(e.getAsFloat()))) {
            issues.add(ip + "." + key + " 需要有限数字，实际: " + e);
        }
    }

    /** 动作元素的结构键白名单：{@code type} 之外的键都必须在 {@code allowed} 里（空 = 该动作无参数）。 */
    private static void rejectActionKeys(JsonObject action, String ip, List<String> issues, String... allowed) {
        for (String key : action.keySet()) {
            if ("type".equals(key)) continue;
            boolean known = false;
            for (String a : allowed) {
                if (a.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                issues.add(ip + "." + key + " 未知键（"
                        + (allowed.length == 0 ? "该动作无参数" : "可用: " + String.join(" / ", allowed)) + "）");
            }
        }
    }

    /** 轨道类型名 → id（精确 id 优先，内置 id 额外接受大小写变化；未知 → {@code null}；与运行期同一口径）。 */
    private static TrackTypeId trackTypeOf(String name) {
        return TrackRegistry.resolve(name);
    }

    /** 轨道类型名清单（报错文案用）。 */
    private static String trackTypesHint() {
        return TrackRegistry.idListForMessage();
    }

    /**
     * 校验 Modifier 参数体（{@code channels} + {@code mode?} + {@code priority?} + 通道值）：
     * clip 级 {@code modifier} 字段与规则动作 {@code camera_set} 共用（同一份参数结构）。
     *
     * @param extraStructuralKeys 额外允许的结构键（{@code camera_set} 传 {@code "type"}；无则空）
     */
    private static void checkModifierBody(JsonObject modifier, String mp, List<String> issues,
                                          String... extraStructuralKeys) {
        int mask = checkModifierChannels(modifier, mp, issues);
        checkModifierMode(modifier, mp, issues);
        checkModifierPriority(modifier, mp, issues);
        for (String name : CameraChannels.NAMES) {
            boolean declared = (mask & CameraChannels.bitOf(name)) != 0;
            if (!declared) {
                if (modifier.has(name)) {
                    issues.add(mp + "." + name + " 未在 channels 中声明（该通道不生效——要么加进 channels，"
                            + "要么删掉该键）");
                }
                continue;
            }
            if (!modifier.has(name)) {
                issues.add(mp + "." + name + " 缺失（channels 已声明该通道，需要它的值）");
                continue;
            }
            checkModifierValue(modifier.get(name), mp + "." + name, name, issues);
        }
        StringBuilder extra = new StringBuilder();
        for (String key : extraStructuralKeys) {
            extra.append(" / ").append(key);
        }
        for (String key : modifier.keySet()) {
            if ("channels".equals(key) || "mode".equals(key) || "priority".equals(key)) continue;
            if (CameraChannels.bitOf(key) != 0) continue;
            if (extra.indexOf(" / " + key) >= 0) continue;
            issues.add(mp + "." + key + " 未知键（结构键: channels / mode / priority" + extra
                    + "；值键: 通道名 " + CameraChannels.namesHint() + "）");
        }
    }

    /** 校验 {@code channels}（生效通道名数组）：非空、六通道名子集、不重复；返回已声明通道的掩码。 */
    private static int checkModifierChannels(JsonObject modifier, String mp, List<String> issues) {
        String hint = CameraChannels.namesHint();
        JsonElement e = modifier.get("channels");
        if (e == null) {
            issues.add(mp + ".channels 缺失（需要通道名数组，至少一个: " + hint + "）");
            return 0;
        }
        if (!e.isJsonArray()) {
            issues.add(mp + ".channels 需要数组（通道名: " + hint + "）");
            return 0;
        }
        JsonArray channels = e.getAsJsonArray();
        if (channels.isEmpty()) {
            issues.add(mp + ".channels 为空（至少声明一个生效通道: " + hint + "）");
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < channels.size(); i++) {
            JsonElement channel = channels.get(i);
            String cp = mp + ".channels[" + i + "]";
            if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isString()) {
                issues.add(cp + " 需要字符串（通道名: " + hint + "）");
                continue;
            }
            String name = channel.getAsString();
            int bit = CameraChannels.bitOf(name);
            if (bit == 0) {
                issues.add(cp + " 未知通道: " + name + "（可选: " + hint + "）");
            } else if ((mask & bit) != 0) {
                issues.add(cp + " 重复通道: " + name);
            } else {
                mask |= bit;
            }
        }
        return mask;
    }

    /** 校验 {@code mode}：{@code add} / {@code multiply} / {@code replace}；缺省 = 逐通道缺省。 */
    private static void checkModifierMode(JsonObject modifier, String mp, List<String> issues) {
        if (!modifier.has("mode")) return;
        JsonElement e = modifier.get("mode");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(mp + ".mode 需要字符串（add / multiply / replace；缺省 = 逐通道缺省）");
            return;
        }
        String mode = e.getAsString();
        if (!"add".equals(mode) && !"multiply".equals(mode) && !"replace".equals(mode)) {
            issues.add(mp + ".mode 未知合成模式: " + mode
                    + "（可选: add / multiply / replace；缺省 = 逐通道缺省）");
        }
    }

    /** 校验 {@code priority}：整数（应用序升序，小者先应用；缺省 0）。 */
    private static void checkModifierPriority(JsonObject modifier, String mp, List<String> issues) {
        if (!modifier.has("priority")) return;
        JsonElement e = modifier.get("priority");
        if (!isNumber(e)) {
            issues.add(mp + ".priority 需要整数（应用序：升序，小者先应用；缺省 0）");
            return;
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            issues.add(mp + ".priority 需要整数（应用序：升序，小者先应用；缺省 0）：" + value);
        }
    }

    /** 校验一个已声明通道的值：{@code position} = 对象 {@code {x, y, z}}；其余五个 = 有限数字。 */
    private static void checkModifierValue(JsonElement e, String path, String name, List<String> issues) {
        if (CameraChannels.POSITION == CameraChannels.bitOf(name)) {
            if (!e.isJsonObject()) {
                issues.add(path + " 需要对象 {x, y, z}（position 是整通道一位，三轴都要写）");
                return;
            }
            JsonObject position = e.getAsJsonObject();
            for (String axis : position.keySet()) {
                if (!"x".equals(axis) && !"y".equals(axis) && !"z".equals(axis)) {
                    issues.add(path + "." + axis + " 未知键（position 只认 x / y / z）");
                }
            }
            for (String axis : new String[]{"x", "y", "z"}) {
                JsonElement component = position.get(axis);
                if (component == null) {
                    issues.add(path + "." + axis + " 缺失（position 的三轴 x / y / z 都要写）");
                } else if (!isNumber(component) || !Double.isFinite(component.getAsDouble())) {
                    issues.add(path + "." + axis + " 需要有限数字（世界坐标，方块）");
                }
            }
            return;
        }
        if (!isNumber(e) || !Float.isFinite(e.getAsFloat())) {
            issues.add(path + " 需要有限数字（REPLACE = 绝对值、ADD = 增量、MULTIPLY = 倍率）");
        }
    }

    /**
     * 校验 {@code meta.screen_layers}。
     *
     * <p>与 {@code ScriptParser#parseScreenLayers} 同一口径：{@code screen_layers} 的值 = 显示
     * （true 显示 / false 隐藏）；未知名按动态层名处理。</p>
     */
    private static void checkScreenLayers(JsonObject root, List<String> issues) {
        if (!root.has("meta") || !root.get("meta").isJsonObject()) return;
        JsonObject meta = root.getAsJsonObject("meta");

        // screen_layers：对象 + 布尔值 + 键域（15 个规范层名 / 动态层名）
        JsonElement layersEl = meta.get("screen_layers");
        if (layersEl != null && !layersEl.isJsonNull()) {
            if (!layersEl.isJsonObject()) {
                issues.add("meta.screen_layers 需要对象 {层名: 布尔}（true = 显示，false = 隐藏），实际: " + layersEl);
            } else {
                for (Map.Entry<String, JsonElement> entry : layersEl.getAsJsonObject().entrySet()) {
                    String key = entry.getKey();
                    if (key.isEmpty()) {
                        issues.add("meta.screen_layers 存在空字符串键（层名不能为空）");
                        continue;
                    }
                    JsonElement v = entry.getValue();
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                        issues.add("meta.screen_layers['" + key + "'] 需要布尔值（true = 显示，false = 隐藏），实际: " + v);
                    }
                }
            }
        }
    }

    /**
     * 校验 {@code meta.base_resolution}（编辑基准分辨率，分辨率转义系数 k 的分母）：
     * 必须是对象且含正整数 {@code w} / {@code h}（{@code > 0}、整数、数值上限为 {@link Integer#MAX_VALUE}），
     * 违规 → 脚本被拒。字段缺省 / JSON null（= 1920×1080）时跳过。
     * 不设分辨率政策上限：{@code 1 ~ Integer.MAX_VALUE} 均合法，上界只是 int 存储表示范围，非分辨率限制。
     */
    private static void checkBaseResolution(JsonObject meta, List<String> issues) {
        if (!meta.has("base_resolution") || meta.get("base_resolution").isJsonNull()) return;
        JsonElement e = meta.get("base_resolution");
        if (!e.isJsonObject()) {
            issues.add("meta.base_resolution 需要对象 {w, h}（像素，正整数；缺省 1920×1080）");
            return;
        }
        JsonObject obj = e.getAsJsonObject();
        checkBaseResolutionComponent(obj, "w", issues);
        checkBaseResolutionComponent(obj, "h", issues);
    }

    /** 校验 {@code meta.base_resolution} 的一个分量：正整数且 ≤ {@link Integer#MAX_VALUE}（无分辨率政策上限）。 */
    private static void checkBaseResolutionComponent(JsonObject obj, String key, List<String> issues) {
        String path = "meta.base_resolution." + key;
        if (!obj.has(key)) {
            issues.add(path + " 缺失（base_resolution 需要正整数 w 与 h；缺省 1920×1080）");
            return;
        }
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            issues.add(path + " 不是数字（需要正整数，缺省 1920×1080）");
            return;
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d != Math.floor(d)) {
            issues.add(path + " 不是整数：" + e);
            return;
        }
        if (d <= 0 || d > Integer.MAX_VALUE) {
            issues.add(path + " 超出范围 1 ~ " + Integer.MAX_VALUE + "（int 表示范围，非分辨率上限）：" + e);
        }
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    /** 校验枚举字段，未知值时报错并列出合法值 */
    private static void checkEnum(JsonObject obj, String path, String key, List<String> issues, String... allowed) {
        if (!obj.has(key)) return;
        String v = obj.get(key).getAsString();
        for (String a : allowed) {
            if (a.equals(v)) return;
        }
        StringBuilder sb = new StringBuilder(path + "." + key + " 未知值: " + v + "（可选: ");
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(allowed[i]);
        }
        sb.append("）");
        issues.add(sb.toString());
    }

    /**
     * 校验 fade 覆盖层的 color 十六进制格式：与运行期 {@code FadeLayer.setColor} 的文档契约一致——
     * #RRGGBB 六位十六进制，前缀 {@code #} 可省，大小写均可。
     * 字段缺省时跳过——运行期默认回退黑色（不告警），仅在作者显式写了非法色时提示。
     */
    private static void checkHexColor(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + key + " 应为字符串颜色（#RRGGBB 六位十六进制，如 \"#000000\"）");
            return;
        }
        String v = e.getAsString();
        String hex = v.startsWith("#") ? v.substring(1) : v;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            issues.add(path + "." + key + " 颜色格式非法: \"" + v + "\"（需 #RRGGBB 六位十六进制，如 \"#000000\"）");
        }
    }

    /** 视觉轨：只有这些轨道支持 pingpong（时间映射往复）。 */
    

    /**
     * 校验 meta.triggers 的声明 id：可选；存在时必须是字符串、非空、匹配
     * {@link TriggerDefinition#ID_REGEX}，且同一脚本内不重复。格式 / 重复归校验器，解析期只判类型。
     */
    private static void validateTriggerIdentities(JsonObject meta, String path, List<String> issues) {
        if (!meta.has("triggers") || !meta.get("triggers").isJsonArray()) return;
        JsonArray triggers = meta.getAsJsonArray("triggers");
        Map<String, Integer> firstSeen = new LinkedHashMap<>();
        for (int i = 0; i < triggers.size(); i++) {
            JsonElement te = triggers.get(i);
            if (!te.isJsonObject()) continue;
            JsonElement idElement = te.getAsJsonObject().get("id");
            String tp = path + ".triggers[" + i + "]";
            if (idElement == null || idElement.isJsonNull()) continue;
            if (!isString(idElement)) {
                issues.add(tp + ".id 需要字符串（匹配 " + TriggerDefinition.ID_REGEX + "）");
                continue;
            }
            String id = idElement.getAsString();
            if (id.isEmpty()) {
                issues.add(tp + ".id 不能为空（匹配 " + TriggerDefinition.ID_REGEX + "）");
                continue;
            }
            if (!TRIGGER_ID_PATTERN.matcher(id).matches()) {
                issues.add(tp + ".id 不合法: " + id + "（需匹配 " + TriggerDefinition.ID_REGEX + "）");
                continue;
            }
            Integer previous = firstSeen.putIfAbsent(id, i);
            if (previous != null) {
                issues.add("触发器 id 重复: " + id + "（出现于 " + path + ".triggers[" + previous + "] 与 " + tp + "）");
            }
        }
    }

    /**
     * 校验 meta.triggers 的 requires 前置条件：requires 必须是数组，元素为字符串脚本 id
     * 或对象型前置条件。字符串按 script_played 校验脚本存在 / 自引用；对象型内置
     * script_played / script_started / script_completed 需要有效的 script 字段。
     */
    private static void validateTriggerRequires(JsonObject meta, String path,
                                                 Collection<String> knownScriptIds, List<String> issues) {
        if (!meta.has("triggers")) return;
        if (!meta.get("triggers").isJsonArray()) {
            issues.add(path + ".triggers 不是数组");
            return;
        }
        JsonArray triggers = meta.getAsJsonArray("triggers");
        String selfId = meta.has("id") && meta.get("id").isJsonPrimitive()
                ? meta.get("id").getAsString() : null;
        for (int i = 0; i < triggers.size(); i++) {
            JsonElement te = triggers.get(i);
            if (!te.isJsonObject()) {
                issues.add(path + ".triggers[" + i + "] 不是对象");
                continue;
            }
            JsonObject t = te.getAsJsonObject();
            String tp = path + ".triggers[" + i + "]";
            if (!t.has("requires")) continue;
            if (!t.get("requires").isJsonArray()) {
                issues.add(tp + ".requires 必须是数组");
                continue;
            }
            JsonArray reqs = t.getAsJsonArray("requires");
            for (int j = 0; j < reqs.size(); j++) {
                JsonElement re = reqs.get(j);
                String rp = tp + ".requires[" + j + "]";
                if (re.isJsonPrimitive() && re.getAsJsonPrimitive().isString()) {
                    String reqId = re.getAsString();
                    if (reqId.isEmpty()) {
                        issues.add(rp + " 前置脚本 id 不能为空");
                        continue;
                    }
                    if (selfId != null && reqId.equals(selfId)) {
                        issues.add(rp + " 自引用自身脚本 '" + reqId + "'（可能永不解锁）");
                    }
                    if (knownScriptIds != null && !knownScriptIds.contains(reqId)) {
                        issues.add(rp + " 指向不存在的脚本 '" + reqId + "'（该触发器将永不触发）");
                    }
                } else if (re.isJsonObject()) {
                    // 对象型前置条件：type / script 字段与跨脚本引用校验（与组合器子条件共用同一套规则）
                    JsonObject reqObj = re.getAsJsonObject();
                    validatePrerequisiteRef(reqObj, reqObj, rp, selfId, knownScriptIds, issues);
                } else {
                    issues.add(rp + " 必须是字符串脚本 id 或对象型前置条件");
                }
            }
        }
    }

    /**
     * 校验 meta.triggers 的 type / conditions 结构：未知类型、conditions 非对象，以及 conditions 字段的
     * schema 通用校验（必填 / 类型 / enum 取值 / 有限数值，见 {@link #validateConditionsBySchema}）。
     * 跨字段语义走专用分支：facing（端点配对 + pitch 钳制）、all_of / any（嵌套结构 + 层数上限）、
     * death（场景组字段）；另报无效字段提示（exit_buffer / on_enter）与轮询间隔。
     */
    private static void validateTriggerConditions(JsonObject meta, String path,
                                                  Collection<String> knownScriptIds, List<String> issues) {
        if (!meta.has("triggers") || !meta.get("triggers").isJsonArray()) return;
        String selfId = meta.has("id") && meta.get("id").isJsonPrimitive()
                ? meta.get("id").getAsString() : null;
        JsonArray triggers = meta.getAsJsonArray("triggers");
        for (int i = 0; i < triggers.size(); i++) {
            JsonElement te = triggers.get(i);
            if (!te.isJsonObject()) continue;
            JsonObject t = te.getAsJsonObject();
            String tp = path + ".triggers[" + i + "]";
            String type = t.has("type") && t.get("type").isJsonPrimitive() ? t.get("type").getAsString() : "";
            JsonObject conditions = null;
            if (t.has("conditions")) {
                if (t.get("conditions").isJsonObject()) {
                    conditions = t.getAsJsonObject("conditions");
                } else {
                    issues.add(tp + ".conditions 必须是对象");
                }
            }
            if (type.isEmpty()) continue;
            TriggerType tt = TriggerRegistry.get(type);
            if (tt == null) {
                issues.add(tp + ".type 未知触发器类型: " + type);
                continue;
            }
            if (conditions != null) {
                if ("facing".equals(type)) {
                    // schema 表达不了：四个角度字段是两对端点，pitch 端点还要按原版 -90~90 钳制
                    validateFacingConditions(conditions, tp + ".conditions", issues);
                } else if ("all_of".equals(type) || "any".equals(type)) {
                    // schema 表达不了：list 元素是 { type, conditions } 结构，子项可嵌套或引用前置条件
                    validateCombinationConditions(conditions, tp + ".conditions", type, 1,
                            selfId, knownScriptIds, continuityWindow(t), issues);
                } else if ("death".equals(type)) {
                    // schema 表达不了：场景组字段互相配合（position+radius / corner1+corner2），文案带场景语义
                    validateDeathConditions(conditions, tp + ".conditions", issues);
                } else {
                    // 已删除的旧写法：entity_kill / inventory 的 mode（数组形态一并由 schema 的类型检查拦住）
                    if (("entity_kill".equals(type) || "inventory".equals(type)) && conditions.has("mode")) {
                        issues.add(tp + ".conditions.mode 已移除：多项判定用 any / all_of 组合器表达");
                    }
                    validateConditionsBySchema(conditions, tp + ".conditions", type, issues);
                }
            }
            if (("facing".equals(type) || "all_of".equals(type) || "any".equals(type))
                    && isPositiveNumber(t.get("exit_buffer"))) {
                issues.add(tp + ".exit_buffer 对 " + type + " 无效（没有可外扩的空间条件），将被忽略");
            }
            checkPollIntervalField(t, "poll_interval", tp, issues);
            validateContinuity(t, tp, type, tt, issues);
            if (tt.getStrategy() == ListenStrategy.EVENT_DRIVEN
                    && t.has("poll_interval") && !t.get("poll_interval").isJsonNull()) {
                issues.add(tp + ".poll_interval 对 " + type + " 无效（事件驱动触发器不参与轮询），将被忽略");
            }
            if ("death".equals(type)) {
                if (isPositiveNumber(t.get("exit_buffer"))) {
                    issues.add(tp + ".exit_buffer 对 death 无效（事件驱动触发器不参与进出区域判定），将被忽略");
                }
                if (t.has("on_enter") && t.get("on_enter").isJsonPrimitive()
                        && t.get("on_enter").getAsJsonPrimitive().isBoolean()
                        && t.get("on_enter").getAsBoolean()) {
                    issues.add(tp + ".on_enter 对 death 无效（事件驱动触发器没有进入/离开语义，"
                            + "而进入状态不会复位）：写了等于只触发一次，建议改用 repeatable 控制");
                }
            }
        }
    }

    /**
     * 校验轮询间隔字段（tick）：触发器级 {@code poll_interval} 与脚本级 {@code meta.trigger_poll_interval}
     * 同口径——存在时必须为 {@link TriggerType#POLL_INTERVAL_MIN}~{@link TriggerType#POLL_INTERVAL_MAX}
     * 的整数；JSON null 视为未写（与解析层同口径，回落下一级）。
     */
    private static void checkPollIntervalField(JsonObject obj, String key, String path, List<String> issues) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return;
        JsonElement e = obj.get(key);
        String fp = path + "." + key;
        String range = TriggerType.POLL_INTERVAL_MIN + "~" + TriggerType.POLL_INTERVAL_MAX;
        if (!isNumber(e)) {
            issues.add(fp + " 需要整数（tick，" + range + "），实际: " + e);
            return;
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d != Math.floor(d)) {
            issues.add(fp + " 不是整数（tick，" + range + "），实际: " + e);
            return;
        }
        int v = (int) d;
        if (v < TriggerType.POLL_INTERVAL_MIN || v > TriggerType.POLL_INTERVAL_MAX) {
            issues.add(fp + " 超出范围 " + range + "（tick），实际: " + v);
        }
    }

    /**
     * 校验触发器级连续语义 {@code continuity}：形态（对象）、字段类型与范围（{@code dwell} / {@code window}
     * 0~1200 整数），以及三个「写了也不会生效」的语义提示：
     * <ul>
     *   <li>{@code sweep} 只对 {@code location} / {@code structure} / {@code facing} 有几何语义；</li>
     *   <li>{@code dwell} 只对轮询类触发器生效，事件驱动类不参与轮询；</li>
     *   <li>{@code window} 只对 {@code all_of} / {@code any} 的事件类子条件生效。</li>
     * </ul>
     * 未知键保持静默（向前兼容，与 conditions 的 schema 校验同口径）。
     */
    private static void validateContinuity(JsonObject t, String tp, String type, TriggerType tt, List<String> issues) {
        if (!t.has("continuity") || t.get("continuity").isJsonNull()) return;
        String cp = tp + ".continuity";
        JsonElement ce = t.get("continuity");
        if (!ce.isJsonObject()) {
            issues.add(cp + " 必须是对象");
            return;
        }
        JsonObject continuity = ce.getAsJsonObject();

        boolean sweep = false;
        if (continuity.has("sweep") && !continuity.get("sweep").isJsonNull()) {
            JsonElement se = continuity.get("sweep");
            if (se.isJsonPrimitive() && se.getAsJsonPrimitive().isBoolean()) {
                sweep = se.getAsBoolean();
            } else {
                issues.add(cp + ".sweep 需要布尔值，实际: " + se);
            }
        }

        int dwell = 0;
        boolean dwellValid = false;
        if (continuity.has("dwell") && !continuity.get("dwell").isJsonNull()) {
            JsonElement de = continuity.get("dwell");
            String range = "0~" + ContinuitySpec.MAX_TICKS;
            if (!isNumber(de)) {
                issues.add(cp + ".dwell 需要整数（tick，" + range + "），实际: " + de);
            } else {
                double d = de.getAsDouble();
                if (!Double.isFinite(d) || d != Math.floor(d)) {
                    issues.add(cp + ".dwell 不是整数（tick，" + range + "），实际: " + de);
                } else if (d < 0 || d > ContinuitySpec.MAX_TICKS) {
                    issues.add(cp + ".dwell 超出范围 " + range + "（tick），实际: " + de);
                } else {
                    dwell = (int) d;
                    dwellValid = true;
                }
            }
        }

        int window = 0;
        boolean windowValid = false;
        if (continuity.has("window") && !continuity.get("window").isJsonNull()) {
            JsonElement we = continuity.get("window");
            String range = "0~" + ContinuitySpec.MAX_TICKS;
            if (!isNumber(we)) {
                issues.add(cp + ".window 需要整数（tick，" + range + "），实际: " + we);
            } else {
                double w = we.getAsDouble();
                if (!Double.isFinite(w) || w != Math.floor(w)) {
                    issues.add(cp + ".window 不是整数（tick，" + range + "），实际: " + we);
                } else if (w < 0 || w > ContinuitySpec.MAX_TICKS) {
                    issues.add(cp + ".window 超出范围 " + range + "（tick），实际: " + we);
                } else {
                    window = (int) w;
                    windowValid = true;
                }
            }
        }

        if (sweep && !isSweepableType(type)) {
            issues.add(cp + ".sweep 对 " + type + " 无几何语义，将被忽略"
                    + "（仅 location / structure / facing / observation 支持段扫掠）");
        }
        if (dwellValid && dwell > 0 && tt.getStrategy() != ListenStrategy.POLLING) {
            issues.add(cp + ".dwell 对 " + type + " 无效（事件驱动触发器不参与轮询，将被忽略）");
        }
        if (windowValid && window > 0 && !isCombinatorType(type)) {
            issues.add(cp + ".window 对 " + type + " 无效（window 只对 all_of / any 的事件类子条件生效，将被忽略）");
        }
    }

    /** {@code continuity.window} 有语义的触发器类型（组合器）。 */
    private static boolean isCombinatorType(String type) {
        return "all_of".equals(type) || "any".equals(type);
    }

    /**
     * 该触发器声明的 {@code continuity.window}（合法整数 {@code 0..MAX_TICKS}）；
     * 缺省 / JSON null / 非法 → 0（非法形态由 {@link #validateContinuity} 另行报错）。
     */
    private static int continuityWindow(JsonObject t) {
        if (!t.has("continuity") || !t.get("continuity").isJsonObject()) return 0;
        JsonElement we = t.getAsJsonObject("continuity").get("window");
        if (we == null || !isNumber(we)) return 0;
        double w = we.getAsDouble();
        if (!Double.isFinite(w) || w != Math.floor(w) || w < 0 || w > ContinuitySpec.MAX_TICKS) return 0;
        return (int) w;
    }

    /** {@code continuity.sweep} 有几何语义的触发器类型。 */
    private static boolean isSweepableType(String type) {
        return "location".equals(type) || "structure".equals(type) || "facing".equals(type)
                || "observation".equals(type);
    }

    /**
     * conditions 通用校验（schema 驱动）：按 {@link TriggerSchemas#all()} 里该类型的字段表逐字段检查
     * 必填缺失 / 类型不符 / enum 取值非法 / 数值非有限。新触发器类型只要在 TriggerSchemas 登记字段，
     * 校验自动生效——不需要在校验器加分支（跨字段语义类型走各自专用分支，见
     * {@link #validateTriggerConditions}）。未登记的字段保持静默（向前兼容，不做未知字段拒绝）。
     */
    private static void validateConditionsBySchema(JsonObject c, String p, String type, List<String> issues) {
        Map<String, FieldDef> fields = TriggerSchemas.all().get(type);
        if (fields == null) return;
        for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
            String key = entry.getKey();
            FieldDef def = entry.getValue();
            String fp = p + "." + key;
            if (!c.has(key)) {
                if (def.required()) issues.add(fp + " 缺失（" + type + " 的必填条件字段）");
                continue;
            }
            JsonElement v = c.get(key);
            switch (def.type()) {
                case "string" -> {
                    if (!isString(v)) issues.add(fp + " 需要字符串");
                }
                case "bool" -> {
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                        issues.add(fp + " 需要布尔值（true / false）");
                    }
                }
                case "map" -> {
                    if (!v.isJsonObject()) issues.add(fp + " 需要对象（形如 { \"x\": ..., \"y\": ..., \"z\": ... }）");
                }
                case "array" -> {
                    if (!v.isJsonArray()) issues.add(fp + " 需要数组");
                }
                case "int" -> {
                    if (!isNumber(v)) {
                        issues.add(fp + " 需要整数");
                    } else if (!isInt32(v)) {
                        issues.add(fp + " 不是整数：" + v);
                    }
                }
                case "float", "number", "double" -> {
                    if (!isNumber(v)) {
                        issues.add(fp + " 需要数字");
                    } else if (!Float.isFinite(v.getAsFloat())) {
                        issues.add(fp + " 不是有限数字（NaN / Infinity）：" + v);
                    }
                }
                case "enum" -> {
                    if (!isString(v) || !def.enumValues().contains(v.getAsString())) {
                        issues.add(fp + " 取值非法: " + v + "（" + type + " 只接受 "
                                + enumLabel(def.enumValues()) + "）");
                    }
                }
                default -> {
                    // 未知字段类型：不报（schema 演进留白；补字段类型时在此加分支）
                }
            }
        }
    }

    /** enum 取值集的可读列表；空串是 schema 里的「显式不限制」取值，渲染成 {@code ""} */
    private static String enumLabel(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(value.isEmpty() ? "\"\"" : value);
        }
        return sb.toString();
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    /** 数值是否为 32 位可表示整数（NaN / Infinity / 小数 / 越界均不算） */
    private static boolean isInt32(JsonElement e) {
        double d = e.getAsDouble();
        return Double.isFinite(d) && d == Math.rint(d)
                && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    private static void validateFacingConditions(JsonObject c, String p, List<String> issues) {
        for (String key : new String[]{"yaw1", "pitch1", "yaw2", "pitch2"}) {
            if (!c.has(key) || !c.get(key).isJsonPrimitive() || !c.get(key).getAsJsonPrimitive().isNumber()) {
                issues.add(p + "." + key + " 缺失或不是数字（facing 需要 yaw1/pitch1/yaw2/pitch2）");
            }
        }
        if (c.has("pitch1") && c.has("pitch2")
                && c.get("pitch1").isJsonPrimitive() && c.get("pitch2").isJsonPrimitive()
                && c.get("pitch1").getAsJsonPrimitive().isNumber()
                && c.get("pitch2").getAsJsonPrimitive().isNumber()) {
            float p1 = c.get("pitch1").getAsFloat();
            float p2 = c.get("pitch2").getAsFloat();
            if (!Float.isFinite(p1) || !Float.isFinite(p2)) {
                issues.add(p + " pitch 端点不是有限数字（NaN / Infinity），该条件将永不满足");
            } else if (p1 < -90f || p1 > 90f || p2 < -90f || p2 > 90f) {
                issues.add(p + " pitch 端点超出 -90~90（原版 pitch 范围），该条件将永不满足");
            }
        }
    }

    /**
     * death 条件校验（场景组，字段全部可选）：dimension / biome 必须是字符串，position / corner1 / corner2
     * 必须是对象（形如 {@code { "x": ..., "y": ..., "z": ... } }），radius 必须是数字。写错类型 = 该条件
     * 永不满足（求值侧按类型不符直接判 false），故报错而非静默忽略。
     */
    private static void validateDeathConditions(JsonObject c, String p, List<String> issues) {
        for (String key : new String[]{"dimension", "biome"}) {
            if (c.has(key) && (!c.get(key).isJsonPrimitive() || !c.get(key).getAsJsonPrimitive().isString())) {
                issues.add(p + "." + key + " 必须是字符串（场景条件按死亡时刻记录判定，如 \"minecraft:the_nether\"）");
            }
        }
        for (String key : new String[]{"position", "corner1", "corner2"}) {
            if (c.has(key) && !c.get(key).isJsonObject()) {
                issues.add(p + "." + key + " 必须是对象（形如 { \"x\": ..., \"y\": ..., \"z\": ... }）");
            }
        }
        if (c.has("radius") && (!c.get("radius").isJsonPrimitive()
                || !c.get("radius").getAsJsonPrimitive().isNumber())) {
            issues.add(p + ".radius 必须是数字（配合 position，缺省 0）");
        }
    }

    /**
     * 校验组合器（all_of / any）的 conditions.list（递归，支持嵌套）：list 必须是非空数组，元素为对象且带非空
     * 字符串 type；子类型可以是已注册的轮询类触发器、已注册前置条件（锁存）或另一个组合器（嵌套）；事件类
     * 只有在触发器声明了 {@code continuity.window > 0} 时才允许（窗口语义）。
     * 嵌套层数 = 本层 + 1（顶层 = 1），超过 {@link Evaluators#MAX_COMBINATION_DEPTH} 报错并停止下钻。
     *
     * @param depth       本组合器所处层数，顶层 = 1（与求值侧同一常量口径）
     * @param windowTicks 该触发器声明的 {@code continuity.window}（tick）；{@code <= 0} = 未启用窗口
     */
    private static void validateCombinationConditions(JsonObject c, String p, String combinator, int depth,
                                                      String selfId, Collection<String> knownScriptIds,
                                                      int windowTicks, List<String> issues) {
        if (!c.has("list") || !c.get("list").isJsonArray()) {
            issues.add(p + ".list 缺失或不是数组（" + combinator
                    + " 需要 \"list\": [ { \"type\": ..., \"conditions\": { ... } }, ... ]）");
            return;
        }
        JsonArray list = c.getAsJsonArray("list");
        if (list.isEmpty()) {
            issues.add(p + ".list 不能为空");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            JsonElement e = list.get(i);
            String sp = p + ".list[" + i + "]";
            if (!e.isJsonObject()) {
                issues.add(sp + " 不是对象（应为 { type, conditions }）");
                continue;
            }
            JsonObject sub = e.getAsJsonObject();
            String subType = sub.has("type") && sub.get("type").isJsonPrimitive()
                    && sub.get("type").getAsJsonPrimitive().isString()
                    ? sub.get("type").getAsString() : null;
            if (subType == null || subType.isEmpty()) {
                issues.add(sp + ".type 缺失或不是非空字符串");
                continue;
            }
            boolean badConditions = sub.has("conditions") && !sub.get("conditions").isJsonObject();
            if (badConditions) {
                issues.add(sp + ".conditions 必须是对象");
            }
            if ("all_of".equals(subType) || "any".equals(subType)) {
                if (depth + 1 > Evaluators.MAX_COMBINATION_DEPTH) {
                    issues.add(sp + " 嵌套超过 " + Evaluators.MAX_COMBINATION_DEPTH + " 层（组合器最大深度，含顶层）: "
                            + subType);
                    continue;
                }
                if (badConditions) continue;   // conditions 结构已报错，无法继续下钻
                JsonObject nested = sub.has("conditions") ? sub.getAsJsonObject("conditions") : new JsonObject();
                validateCombinationConditions(nested, sp + ".conditions", subType, depth + 1,
                        selfId, knownScriptIds, windowTicks, issues);
                continue;
            }
            if (PrerequisiteRegistry.has(subType)) {
                // 前置条件子项（锁存语义）：数据与触发器一致地放在 conditions 里，复用 requires 的同一套字段检查
                JsonObject data = sub.has("conditions") && sub.get("conditions").isJsonObject()
                        ? sub.getAsJsonObject("conditions") : new JsonObject();
                validatePrerequisiteRef(sub, data, sp + ".conditions", selfId, knownScriptIds, issues);
                continue;
            }
            TriggerType tt = TriggerRegistry.get(subType);
            if (tt == null) {
                issues.add(sp + ".type 未知触发器类型，也不是已注册的前置条件: " + subType);
                continue;
            }
            if (tt.getStrategy() != ListenStrategy.POLLING) {
                // 事件类子条件按「窗口内是否发生过」求值：只有触发器声明了 continuity.window > 0 才有窗口语义。
                if (windowTicks <= 0) {
                    issues.add(sp + " 事件类触发器放进 " + combinator
                            + " 需要触发器声明 continuity.window > 0（窗口语义），当前 window=0: " + subType);
                }
            }
        }
    }

    /**
     * 前置条件的公共校验（{@code requires} 的对象型写法与组合器子条件共用同一套规则）：
     * type 必需且非空；内置 {@code script_played} / {@code script_started} / {@code script_completed}
     * 的数据对象必须提供 script 字段，并做自引用与跨脚本存在性检查。未知 / 自定义类型不在这里硬报错——
     * 是否允许由调用方判定（requires 允许其他模组注册后使用；组合器子条件只允许已注册类型）。
     *
     * @param typeHolder 带 {@code type} 的对象（requires 元素 / 组合器子条件）
     * @param data       该前置条件的数据对象（requires 元素自身；组合器子条件为 {@code conditions}）
     * @param dataPath   {@code data} 的路径前缀（报错定位用）
     */
    private static void validatePrerequisiteRef(JsonObject typeHolder, JsonObject data, String dataPath,
                                                String selfId, Collection<String> knownScriptIds,
                                                List<String> issues) {
        if (!typeHolder.has("type") || !typeHolder.get("type").isJsonPrimitive()
                || !typeHolder.get("type").getAsJsonPrimitive().isString()) {
            issues.add(dataPath + " 对象型前置条件必须包含字符串 type");
            return;
        }
        String type = typeHolder.get("type").getAsString();
        if (type.isEmpty()) {
            issues.add(dataPath + " 前置条件 type 不能为空");
            return;
        }
        boolean needsScript = type.equals("script_played")
                || type.equals("script_started")
                || type.equals("script_completed");
        if (!needsScript) return;
        if (!data.has("script") || !data.get("script").isJsonPrimitive()
                || !data.get("script").getAsJsonPrimitive().isString()) {
            issues.add(dataPath + ".script 缺失或不是字符串（script_* 前置条件需要 script 字段）");
            return;
        }
        String reqId = data.get("script").getAsString();
        if (reqId.isEmpty()) {
            issues.add(dataPath + ".script 不能为空");
            return;
        }
        if (selfId != null && reqId.equals(selfId)) {
            issues.add(dataPath + ".script 自引用自身脚本 '" + reqId + "'（可能永不解锁或自我循环）");
        }
        if (knownScriptIds != null && !knownScriptIds.contains(reqId)) {
            issues.add(dataPath + ".script 指向不存在的脚本 '" + reqId + "'（该前置条件永不满足）");
        }
    }

    private static boolean isPositiveNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsFloat() > 0f;
    }

    private static boolean isKnownType(String type) {
        return TrackRegistry.resolve(type) != null;
    }

    private static void requireString(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key) || obj.get(key).getAsString().isEmpty()) {
            issues.add(path + "." + key + " 缺失");
        }
    }

    /**
     * 校验字符串字段的最大长度（按 UTF-16 码元数，与 {@code ScriptParser#parseMeta} 同一口径：
     * {@code meta.name} ≤ 50、{@code meta.author} ≤ 30）；字段缺省 / 非字符串时跳过。
     */
    private static void checkMaxLength(JsonObject obj, String path, String key, int max, List<String> issues) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) return;
        int len = obj.get(key).getAsString().length();
        if (len > max) {
            issues.add(path + "." + key + " 最长 " + max + " 字符，实际: " + len);
        }
    }

    
}
