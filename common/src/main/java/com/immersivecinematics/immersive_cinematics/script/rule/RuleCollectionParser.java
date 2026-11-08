package com.immersivecinematics.immersive_cinematics.script.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitPolicy;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 脚本级规则集合的解析器：{@code rules[]}（定义表）+ {@code rule_bindings[]}（绑定表）。
 *
 * <p>口径（{@code ROUND-I-4-MAP} §1.2–§1.5）：
 * <ul>
 *   <li>定义：{@code id} 必填唯一、{@code condition} 必填对象（保留原文，不解析条件树）、
 *       {@code actions} 必填非空数组、{@code policy} 可省且键白名单；</li>
 *   <li>绑定：{@code id} 必填唯一、{@code rule} 命中定义、{@code clip} 命中片段、
 *       {@code gate.at} 必填有限数且在片段时长范围内、{@code control} 可省（缺省 observe）、
 *       {@code schedule} / {@code exit} 可省；未知键报错；</li>
 *   <li>解析期首个错误即抛 {@link ScriptParseException}（不做多错误汇总，汇总归校验器）。</li>
 * </ul>
 *
 * <p>{@code fact} 调度引用另一条绑定：绑定的构造按依赖顺序进行（前向引用合法，循环依赖报错），
 * 这样 {@link RuleRelease#source()} 始终指向真实绑定对象，无需可变字段。</p>
 */
public final class RuleCollectionParser {

    /** 规则 / 绑定 id 的合法形态（与片段 id 同字符集，便于编辑器与命令引用）。 */
    public static final String ID_PATTERN = "^[A-Za-z0-9_]{1,64}$";

    private static final Pattern ID = Pattern.compile(ID_PATTERN);

    private static final List<String> DEFINITION_KEYS = List.of("id", "condition", "actions", "policy");
    private static final List<String> POLICY_KEYS =
            List.of("once", "cooldown", "mode", "dwell", "timeout", "on_timeout");
    private static final List<String> BINDING_KEYS =
            List.of("id", "rule", "clip", "gate", "control", "schedule", "exit");

    private RuleCollectionParser() {}

    /** id 形态是否合法（解析器与校验器同一单源）。 */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * 解析脚本级规则集合。
     *
     * @param root     脚本根对象
     * @param resolver 片段解析器
     * @param basePath 宿主路径前缀（{@code null} / 空 / {@code "<root>"} = 不加前缀）
     * @return 不可变规则集合（两个顶层键都缺省 → {@link RuleCollection#EMPTY}）
     * @throws ScriptParseException 结构 / 引用非法
     */
    public static RuleCollection parse(JsonObject root, ClipResolver resolver, String basePath)
            throws ScriptParseException {
        if (root == null) return RuleCollection.EMPTY;
        boolean hasDefinitions = root.has("rules");
        boolean hasBindings = root.has("rule_bindings");
        if (!hasDefinitions && !hasBindings) return RuleCollection.EMPTY;

        List<RuleDefinition> definitions = parseDefinitions(root, basePath);
        Map<String, RuleDefinition> definitionsById = new LinkedHashMap<>(definitions.size());
        for (RuleDefinition definition : definitions) {
            definitionsById.put(definition.id(), definition);
        }
        List<RuleBinding> bindings = parseBindings(root, resolver, definitionsById, basePath);
        return RuleCollection.of(definitions, bindings);
    }

    // ========== 定义表 ==========

    private static List<RuleDefinition> parseDefinitions(JsonObject root, String basePath)
            throws ScriptParseException {
        String path = path(basePath, "rules");
        JsonElement element = root.get("rules");
        if (element == null) return List.of();
        if (!element.isJsonArray()) {
            throw new ScriptParseException(path, "需要数组（规则定义表；缺省 = 无规则）");
        }
        JsonArray array = element.getAsJsonArray();
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<RuleDefinition> definitions = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String rulePath = path + "[" + i + "]";
            JsonElement entry = array.get(i);
            if (!entry.isJsonObject()) {
                throw new ScriptParseException(rulePath, "需要对象（规则定义）");
            }
            definitions.add(parseDefinition(entry.getAsJsonObject(), rulePath, i, seen));
        }
        return List.copyOf(definitions);
    }

    private static RuleDefinition parseDefinition(JsonObject obj, String path, int index,
                                                  Map<String, Integer> seen) throws ScriptParseException {
        rejectUnknown(obj, path, DEFINITION_KEYS);

        String id = requireId(obj, path);
        Integer previous = seen.putIfAbsent(id, index);
        if (previous != null) {
            throw new ScriptParseException(path + ".id",
                    "重复的规则 id: " + id + "（已出现于 rules[" + previous + "]）");
        }

        JsonObject condition = requireObject(obj, path, "condition",
                "需要对象（条件树，如 {\"type\":\"all_of\",\"list\":[...]}）");
        List<JsonObject> actions = requireActions(obj, path);

        boolean once = RuleDefinition.DEFAULT_ONCE;
        double cooldown = RuleDefinition.DEFAULT_COOLDOWN;
        String mode = RuleDefinition.MODE_ENTER;
        double dwell = RuleDefinition.DEFAULT_DWELL;
        double timeout = RuleDefinition.TIMEOUT_NONE;
        List<JsonObject> onTimeout = List.of();

        if (obj.has("policy")) {
            JsonElement policyElement = obj.get("policy");
            if (!policyElement.isJsonObject()) {
                throw new ScriptParseException(path + ".policy",
                        "需要对象（策略字段: " + String.join(" / ", POLICY_KEYS) + "）");
            }
            JsonObject policy = policyElement.getAsJsonObject();
            String policyPath = path + ".policy";
            rejectUnknown(policy, policyPath, POLICY_KEYS);

            once = policy.has("once") ? requireBoolean(policy, policyPath, "once") : RuleDefinition.DEFAULT_ONCE;
            cooldown = policy.has("cooldown")
                    ? requireNonNegative(policy, policyPath, "cooldown") : RuleDefinition.DEFAULT_COOLDOWN;
            mode = policy.has("mode") ? requireMode(policy, policyPath) : RuleDefinition.MODE_ENTER;
            dwell = policy.has("dwell")
                    ? requireNonNegative(policy, policyPath, "dwell") : RuleDefinition.DEFAULT_DWELL;
            timeout = policy.has("timeout")
                    ? requireTimeout(policy, policyPath) : RuleDefinition.TIMEOUT_NONE;
            if (policy.has("on_timeout")) {
                onTimeout = requireActions(policy, policyPath, "on_timeout", false);
            }
        }

        return new RuleDefinition(id, condition.deepCopy(), actions, once, cooldown, mode, dwell, timeout, onTimeout);
    }

    // ========== 绑定表 ==========

    /** 第一遍解析出的绑定原始数据（调度依赖尚待解析）。 */
    private static final class RawBinding {
        final String id;
        final String path;
        final RuleDefinition definition;
        final Clip target;
        final double gateTime;
        final RuleControl control;
        final JsonObject schedule;
        final String scheduleDependency;
        final ClipExitPolicy exit;

        RawBinding(String id, String path, RuleDefinition definition, Clip target,
                   double gateTime, RuleControl control, JsonObject schedule,
                   String scheduleDependency, ClipExitPolicy exit) {
            this.id = id;
            this.path = path;
            this.definition = definition;
            this.target = target;
            this.gateTime = gateTime;
            this.control = control;
            this.schedule = schedule;
            this.scheduleDependency = scheduleDependency;
            this.exit = exit;
        }
    }

    private static List<RuleBinding> parseBindings(JsonObject root, ClipResolver resolver,
                                                   Map<String, RuleDefinition> definitionsById,
                                                   String basePath)
            throws ScriptParseException {
        String path = path(basePath, "rule_bindings");
        JsonElement element = root.get("rule_bindings");
        if (element == null) return List.of();
        if (!element.isJsonArray()) {
            throw new ScriptParseException(path, "需要数组（规则绑定表；缺省 = 无绑定）");
        }
        JsonArray array = element.getAsJsonArray();

        // 第 0 遍：收集绑定 id（fact 调度允许前向引用，需要先有完整 id 表）
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            String bindingPath = path + "[" + i + "]";
            JsonElement entry = array.get(i);
            if (!entry.isJsonObject()) {
                throw new ScriptParseException(bindingPath, "需要对象（规则绑定）");
            }
            JsonObject obj = entry.getAsJsonObject();
            if (!obj.has("id")) {
                throw new ScriptParseException(bindingPath + ".id", "缺失（绑定 id 必填且脚本内唯一）");
            }
            String id = requireId(obj, bindingPath);
            Integer previous = indexById.putIfAbsent(id, i);
            if (previous != null) {
                throw new ScriptParseException(bindingPath + ".id",
                        "重复的绑定 id: " + id + "（已出现于 rule_bindings[" + previous + "]）");
            }
        }
        Set<String> bindingIds = new LinkedHashSet<>(indexById.keySet());

        // 第 1 遍：结构 / 引用 / 调度结构（按声明顺序报出首个错误）
        List<RawBinding> raws = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            raws.add(parseRawBinding(array.get(i).getAsJsonObject(), path + "[" + i + "]",
                    resolver, definitionsById, bindingIds));
        }

        // 第 2 遍：按 fact 调度依赖顺序构造绑定（前向引用合法；循环依赖报错）
        RuleBinding[] built = new RuleBinding[raws.size()];
        byte[] state = new byte[raws.size()];
        for (int i = 0; i < raws.size(); i++) {
            buildBinding(i, raws, indexById, built, state, resolver);
        }

        List<RuleBinding> bindings = new ArrayList<>(raws.size());
        for (int i = 0; i < raws.size(); i++) {
            bindings.add(built[i]);
        }
        return List.copyOf(bindings);
    }

    private static RawBinding parseRawBinding(JsonObject obj, String path, ClipResolver resolver,
                                              Map<String, RuleDefinition> definitionsById,
                                              Set<String> bindingIds) throws ScriptParseException {
        rejectUnknown(obj, path, BINDING_KEYS);

        String id = requireId(obj, path);

        String ruleId = requireString(obj, path, "rule");
        RuleDefinition definition = definitionsById.get(ruleId);
        if (definition == null) {
            throw new ScriptParseException(path + ".rule", "未知规则引用: " + ruleId);
        }

        String clipId = requireString(obj, path, "clip");
        Clip target = resolver.resolve(clipId);
        if (target == null) {
            throw new ScriptParseException(path + ".clip", "未知片段引用: " + clipId);
        }

        double gateTime = parseGate(obj, path, target);

        RuleControl control = RuleControl.OBSERVE;
        if (obj.has("control")) {
            JsonElement controlElement = obj.get("control");
            if (!controlElement.isJsonPrimitive() || !controlElement.getAsJsonPrimitive().isString()) {
                throw new ScriptParseException(path + ".control",
                        "需要字符串（" + String.join(" / ", RuleControl.SCRIPT_NAMES) + "）");
            }
            control = RuleControl.fromScriptName(controlElement.getAsString());
            if (control == null) {
                throw new ScriptParseException(path + ".control", "未知控制方式: " + controlElement.getAsString()
                        + "（可选: " + String.join(" / ", RuleControl.SCRIPT_NAMES) + "）");
            }
        }

        JsonObject schedule = null;
        String dependency = null;
        if (obj.has("schedule")) {
            JsonElement scheduleElement = obj.get("schedule");
            if (!scheduleElement.isJsonObject()) {
                throw new ScriptParseException(path + ".schedule",
                        "需要对象（释放条件: " + String.join(" / ", RuleReleaseKind.SCRIPT_NAMES) + "）");
            }
            schedule = scheduleElement.getAsJsonObject();
            dependency = RuleSchedule.validate(schedule, resolver, bindingIds, path + ".schedule");
        }

        ClipExitPolicy exit = null;
        if (obj.has("exit")) {
            JsonElement exitElement = obj.get("exit");
            if (!exitElement.isJsonObject()) {
                throw new ScriptParseException(path + ".exit", "需要对象（出口策略）");
            }
            exit = ClipExitPolicyParser.parse(exitElement.getAsJsonObject(), resolver, path + ".exit");
        }

        return new RawBinding(id, path, definition, target, gateTime, control, schedule, dependency, exit);
    }

    private static double parseGate(JsonObject obj, String path, Clip target) throws ScriptParseException {
        if (!obj.has("gate")) {
            throw new ScriptParseException(path + ".gate",
                    "缺失（时间门必填：{\"at\": <目标片段执行局部秒>}）");
        }
        JsonElement gateElement = obj.get("gate");
        if (!gateElement.isJsonObject()) {
            throw new ScriptParseException(path + ".gate", "需要对象 {\"at\": <秒>}");
        }
        JsonObject gate = gateElement.getAsJsonObject();
        for (String key : gate.keySet()) {
            if (!"at".equals(key)) {
                throw new ScriptParseException(path + ".gate." + key, "未知键（gate 只有 at）");
            }
        }
        JsonElement atElement = gate.get("at");
        if (atElement == null || !isNumber(atElement)) {
            throw new ScriptParseException(path + ".gate.at", "缺失或不是数字（执行局部秒）");
        }
        double at = atElement.getAsDouble();
        if (!Double.isFinite(at) || at < 0.0) {
            throw new ScriptParseException(path + ".gate.at", "需要 >= 0 的有限数字，实际: " + atElement);
        }
        if (target.isInfinite()) {
            return at;
        }
        double duration = target.getDuration();
        if (at > duration) {
            throw new ScriptParseException(path + ".gate.at", "越界: " + at + "（片段 "
                    + target.scriptClipId() + " 时长 " + duration + "，允许范围 0 ~ " + duration + "）");
        }
        return at;
    }

    private static void buildBinding(int index, List<RawBinding> raws, Map<String, Integer> indexById,
                                     RuleBinding[] built, byte[] state, ClipResolver resolver)
            throws ScriptParseException {
        if (state[index] == 2) return;
        RawBinding raw = raws.get(index);
        if (state[index] == 1) {
            throw new ScriptParseException(raw.path + ".schedule.binding",
                    "fact 调度形成循环依赖（绑定 " + raw.id + " 间接引用自身）");
        }
        state[index] = 1;
        if (raw.scheduleDependency != null) {
            Integer dependency = indexById.get(raw.scheduleDependency);
            if (dependency != null) {
                buildBinding(dependency, raws, indexById, built, state, resolver);
            }
        }
        RuleSchedule schedule = raw.schedule == null
                ? RuleSchedule.IMMEDIATE
                : RuleSchedule.parse(raw.schedule, resolver,
                        bindingId -> {
                            Integer at = indexById.get(bindingId);
                            return at == null ? null : built[at];
                        },
                        raw.path + ".schedule");
        built[index] = new RuleBinding(raw.id, raw.definition, raw.target, raw.gateTime,
                raw.control, schedule, raw.exit);
        state[index] = 2;
    }

    // ========== 公共结构助手 ==========

    /** 宿主路径：{@code basePath} 为空 / {@code "<root>"} 时不加前缀。 */
    private static String path(String basePath, String local) {
        if (basePath == null || basePath.isEmpty() || "<root>".equals(basePath)) return local;
        return basePath + "." + local;
    }

    private static String requireId(JsonObject obj, String path) throws ScriptParseException {
        String id = requireString(obj, path, "id");
        if (!isValidId(id)) {
            throw new ScriptParseException(path + ".id",
                    "不合法: " + id + "（需匹配 " + ID_PATTERN + "）");
        }
        return id;
    }

    private static String requireString(JsonObject obj, String path, String key) throws ScriptParseException {
        JsonElement element = obj.get(key);
        if (element == null || !isString(element) || element.getAsString().isEmpty()) {
            throw new ScriptParseException(path + "." + key, "缺失或不是非空字符串");
        }
        return element.getAsString();
    }

    private static JsonObject requireObject(JsonObject obj, String path, String key, String message)
            throws ScriptParseException {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new ScriptParseException(path + "." + key, message);
        }
        return element.getAsJsonObject();
    }

    private static List<JsonObject> requireActions(JsonObject obj, String path) throws ScriptParseException {
        return requireActions(obj, path, "actions", true);
    }

    private static List<JsonObject> requireActions(JsonObject obj, String path, String key, boolean required)
            throws ScriptParseException {
        String fieldPath = path + "." + key;
        JsonElement element = obj.get(key);
        if (element == null) {
            if (required) {
                throw new ScriptParseException(fieldPath, "缺失（规则至少一个动作）");
            }
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new ScriptParseException(fieldPath, "需要数组（元素 = 动作对象）");
        }
        JsonArray array = element.getAsJsonArray();
        if (required && array.isEmpty()) {
            throw new ScriptParseException(fieldPath, "为空（规则至少一个动作）");
        }
        List<JsonObject> actions = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement entry = array.get(i);
            if (!entry.isJsonObject()) {
                throw new ScriptParseException(fieldPath + "[" + i + "]", "需要对象（动作元素）");
            }
            actions.add(entry.getAsJsonObject().deepCopy());
        }
        return List.copyOf(actions);
    }

    private static boolean requireBoolean(JsonObject obj, String path, String key) throws ScriptParseException {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ScriptParseException(path + "." + key, "需要布尔值，实际: " + element);
        }
        return element.getAsBoolean();
    }

    private static double requireNonNegative(JsonObject obj, String path, String key)
            throws ScriptParseException {
        JsonElement element = obj.get(key);
        if (element == null || !isNumber(element)) {
            throw new ScriptParseException(path + "." + key, "需要数字（秒，>= 0）");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0.0) {
            throw new ScriptParseException(path + "." + key, "需要 >= 0 的有限数字（秒），实际: " + element);
        }
        return value;
    }

    private static String requireMode(JsonObject obj, String path) throws ScriptParseException {
        JsonElement element = obj.get("mode");
        if (element == null || !isString(element) || !RuleDefinition.MODES.contains(element.getAsString())) {
            throw new ScriptParseException(path + ".mode", "需要 enter / dwell（缺省 enter），实际: " + element);
        }
        return element.getAsString();
    }

    private static double requireTimeout(JsonObject obj, String path) throws ScriptParseException {
        JsonElement element = obj.get("timeout");
        if (element == null || !isNumber(element)) {
            throw new ScriptParseException(path + ".timeout", "需要数字（秒，> 0；缺省 -1 = 无限等待）");
        }
        double value = element.getAsDouble();
        if (value != RuleDefinition.TIMEOUT_NONE && (!Double.isFinite(value) || !(value > 0.0))) {
            throw new ScriptParseException(path + ".timeout",
                    "需要 > 0 的有限数字或 -1（无限等待），实际: " + element);
        }
        return value;
    }

    private static void rejectUnknown(JsonObject obj, String path, List<String> allowed)
            throws ScriptParseException {
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new ScriptParseException(path + "." + key,
                        "未知键（可用: " + String.join(" / ", allowed) + "）");
            }
        }
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }
}
