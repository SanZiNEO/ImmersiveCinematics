package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 判定规则（clip 级 {@code rules[]} 的一条）：条件 + 动作的不可变数据载体（0.3.6 C6-1）。
 *
 * 两种形态（互斥；语义见 plans/0.3.6/C-trigger/condition-system.md §2）：{@code if} = 持续规则
 * （条件成立瞬间触发 {@code do}）；{@code until} = 阻塞规则（挂起本实例播放时钟直到条件成立 / 超时）。
 *
 * 条件（{@code if} / {@code until} 的值）与动作（{@code do} / {@code on_timeout} 的元素）保留原始 JSON：
 * 条件求值归判定源（C6-2），动作执行归动作集（C6-3）——本层只解析结构，不改写内容。
 *
 * 字段与缺省：{@code once} = 只触发一次（缺省 {@code true}）、{@code cooldown} = 触发后静默秒数
 * （缺省 0）、{@code mode} = {@code enter} 瞬时边沿 / {@code dwell} 驻留成立（缺省 {@code enter}）、
 * {@code dwell} = 驻留判定阈值秒数（缺省 0 = 即刻；仅 {@code mode: dwell} 下生效）、
 * {@code timeout} = 阻塞等待上限（秒；缺省 -1 = 无限等待）、{@code on_timeout} = 超时动作（缺省空）。
 *
 * 线程 / 存活期：仅客户端主线程；解析期一次建成、播放期只读（改规则 = 重新解析）。
 */
public final class ClipRule {

    /** 形态：{@code if} = 持续规则（不挂起播放时钟）。 */
    public static final String KIND_IF = "if";

    /** 形态：{@code until} = 阻塞规则（挂起播放时钟直到条件成立 / 超时）。 */
    public static final String KIND_UNTIL = "until";

    /** 触发方式：{@code enter} = 条件成立瞬间（边沿）触发。 */
    public static final String MODE_ENTER = "enter";

    /** 触发方式：{@code dwell} = 条件驻留成立才触发。 */
    public static final String MODE_DWELL = "dwell";

    /** 无超时（{@code timeout} 缺省）：阻塞等待无限长。 */
    public static final float TIMEOUT_NONE = -1f;

    /** 无驻留阈值（{@code dwell} 缺省）：条件成立即刻触发。 */
    public static final float DWELL_NONE = 0f;

    /** 形态：{@link #KIND_IF} 或 {@link #KIND_UNTIL}。 */
    private final String kind;

    /** 条件原文（{@code if} / {@code until} 的对象；求值归判定源，本层只读）。 */
    private final JsonObject condition;

    /** 触发动作原文（{@code do} 的元素，至少一个；执行归动作集）。 */
    private final List<JsonObject> actions;

    /** 是否只触发一次（缺省 {@code true}）。 */
    private final boolean once;

    /** 触发后静默时长（秒，≥ 0；缺省 0 = 不静默）。 */
    private final float cooldown;

    /** 触发方式（{@link #MODE_ENTER} / {@link #MODE_DWELL}）。 */
    private final String mode;

    /** 驻留判定阈值（秒，≥ 0；{@link #DWELL_NONE} = 即刻触发）。 */
    private final float dwell;

    /** 阻塞等待上限（秒，> 0；{@link #TIMEOUT_NONE} = 无限等待）。 */
    private final float timeout;

    /** 超时动作原文（缺省空表；只在 {@code until} 形态且超时先到时执行）。 */
    private final List<JsonObject> onTimeout;

    private ClipRule(String kind, JsonObject condition, List<JsonObject> actions, boolean once,
                     float cooldown, String mode, float dwell, float timeout, List<JsonObject> onTimeout) {
        this.kind = kind;
        this.condition = condition;
        this.actions = actions;
        this.once = once;
        this.cooldown = cooldown;
        this.mode = mode;
        this.dwell = dwell;
        this.timeout = timeout;
        this.onTimeout = onTimeout;
    }

    /**
     * 解析一条规则；结构非法（形态缺失 / 互斥 / 字段类型不符）抛解析异常走 {@link ScriptParser} 错误链。
     *
     * @param value 规则 JSON（对象）
     * @param path  报错用路径（如 {@code timeline.tracks[0].clips[1].rules[0]}）
     * @return 解析后的不可变规则
     * @throws ScriptParser.ScriptParseException 结构非法
     */
    public static ClipRule parse(JsonElement value, String path) throws ScriptParser.ScriptParseException {
        if (!value.isJsonObject()) {
            throw new ScriptParser.ScriptParseException(path,
                    "需要对象 {if|until, do, once?, cooldown?, mode?, timeout?, on_timeout?}");
        }
        JsonObject obj = value.getAsJsonObject();
        boolean hasIf = obj.has("if");
        boolean hasUntil = obj.has("until");
        if (hasIf == hasUntil) {
            throw new ScriptParser.ScriptParseException(path, hasIf
                    ? "if 与 until 互斥（一条规则只能一种形态：if = 持续判定，until = 挂起等待）"
                    : "缺少条件（if / until 恰写一个）");
        }
        String kind = hasIf ? KIND_IF : KIND_UNTIL;
        JsonObject condition = requireCondition(obj.get(kind), path + "." + kind);
        List<JsonObject> actions = parseActionList(obj.get("do"), path + ".do", true);
        List<JsonObject> onTimeout = parseActionList(obj.get("on_timeout"), path + ".on_timeout", false);
        return new ClipRule(kind, condition, actions, parseOnce(obj, path), parseCooldown(obj, path),
                parseMode(obj, path), parseDwell(obj, path), parseTimeout(obj, path), onTimeout);
    }

    /** 条件对象（{@code if} / {@code until} 的值）：必须是对象，内容原样保留（求值归判定源）。 */
    private static JsonObject requireCondition(JsonElement e, String path) throws ScriptParser.ScriptParseException {
        if (!e.isJsonObject()) {
            throw new ScriptParser.ScriptParseException(path,
                    "需要对象（条件定义，如 {\"type\": \"time\", \"at\": 12}）");
        }
        return e.getAsJsonObject();
    }

    /** 动作数组：{@code required} 时空数组也拒（{@code do} 至少要一个动作）；元素必须是对象。 */
    private static List<JsonObject> parseActionList(JsonElement e, String path, boolean required)
            throws ScriptParser.ScriptParseException {
        if (e == null) {
            if (required) throw new ScriptParser.ScriptParseException(path, "缺失（规则必须声明 do 动作数组）");
            return List.of();
        }
        if (!e.isJsonArray()) {
            throw new ScriptParser.ScriptParseException(path, "需要数组（元素 = 动作对象）");
        }
        JsonArray arr = e.getAsJsonArray();
        if (required && arr.isEmpty()) {
            throw new ScriptParser.ScriptParseException(path, "为空（规则至少一个动作）");
        }
        List<JsonObject> actions = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                throw new ScriptParser.ScriptParseException(path + "[" + i + "]", "需要对象（动作元素）");
            }
            actions.add(el.getAsJsonObject());
        }
        return List.copyOf(actions);
    }

    /** {@code once}（缺省 true）：布尔值。 */
    private static boolean parseOnce(JsonObject obj, String path) throws ScriptParser.ScriptParseException {
        if (!obj.has("once")) return true;
        JsonElement e = obj.get("once");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new ScriptParser.ScriptParseException(path + ".once",
                    "需要布尔值（true = 只触发一次；缺省 true）");
        }
        return e.getAsBoolean();
    }

    /** {@code cooldown}（缺省 0）：有限非负数，单位秒。 */
    private static float parseCooldown(JsonObject obj, String path) throws ScriptParser.ScriptParseException {
        if (!obj.has("cooldown")) return 0f;
        JsonElement e = obj.get("cooldown");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParser.ScriptParseException(path + ".cooldown",
                    "需要数字（秒，≥ 0；缺省 0 = 不静默）");
        }
        float v = e.getAsFloat();
        if (!Float.isFinite(v) || v < 0f) {
            throw new ScriptParser.ScriptParseException(path + ".cooldown", "需要 ≥ 0 的有限数字（秒）：" + e);
        }
        return v;
    }

    /** {@code mode}（缺省 enter）：{@code enter} / {@code dwell} 之一。 */
    private static String parseMode(JsonObject obj, String path) throws ScriptParser.ScriptParseException {
        if (!obj.has("mode")) return MODE_ENTER;
        JsonElement e = obj.get("mode");
        String mode = e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
        if (!MODE_ENTER.equals(mode) && !MODE_DWELL.equals(mode)) {
            throw new ScriptParser.ScriptParseException(path + ".mode",
                    "需要 enter / dwell（缺省 enter），实际: " + e);
        }
        return mode;
    }

    /** {@code dwell}（缺省 0 = 即刻）：有限非负数，单位秒；只在 {@code mode: dwell} 下参与判定。 */
    private static float parseDwell(JsonObject obj, String path) throws ScriptParser.ScriptParseException {
        if (!obj.has("dwell")) return DWELL_NONE;
        JsonElement e = obj.get("dwell");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParser.ScriptParseException(path + ".dwell",
                    "需要数字（秒，≥ 0；缺省 0 = 即刻）");
        }
        float v = e.getAsFloat();
        if (!Float.isFinite(v) || v < 0f) {
            throw new ScriptParser.ScriptParseException(path + ".dwell", "需要 ≥ 0 的有限数字（秒）：" + e);
        }
        return v;
    }

    /** {@code timeout}（缺省 -1 = 无限等待）：有限正数，单位秒。 */
    private static float parseTimeout(JsonObject obj, String path) throws ScriptParser.ScriptParseException {
        if (!obj.has("timeout")) return TIMEOUT_NONE;
        JsonElement e = obj.get("timeout");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParser.ScriptParseException(path + ".timeout",
                    "需要数字（秒，> 0；缺省 -1 = 无限等待）");
        }
        float v = e.getAsFloat();
        if (!Float.isFinite(v) || !(v > 0f)) {
            throw new ScriptParser.ScriptParseException(path + ".timeout", "需要 > 0 的有限数字（秒）：" + e);
        }
        return v;
    }

    /** 形态（{@link #KIND_IF} / {@link #KIND_UNTIL}）。 */
    public String getKind() {
        return kind;
    }

    /** 条件原文（只读；求值归判定源）。 */
    public JsonObject getCondition() {
        return condition;
    }

    /** 触发动作原文（只读，至少一个；执行归动作集）。 */
    public List<JsonObject> getActions() {
        return actions;
    }

    /** 是否只触发一次（缺省 true）。 */
    public boolean isOnce() {
        return once;
    }

    /** 触发后静默时长（秒，≥ 0；0 = 不静默）。 */
    public float getCooldown() {
        return cooldown;
    }

    /** 触发方式（{@link #MODE_ENTER} / {@link #MODE_DWELL}）。 */
    public String getMode() {
        return mode;
    }

    /** 驻留判定阈值（秒，≥ 0；{@link #DWELL_NONE} = 即刻触发；仅 {@code dwell} 模式参与判定）。 */
    public float getDwell() {
        return dwell;
    }

    /** 阻塞等待上限（秒，> 0；{@link #TIMEOUT_NONE} = 无限等待）。 */
    public float getTimeout() {
        return timeout;
    }

    /** 超时动作原文（可能为空表；只在 until 形态超时先到时执行）。 */
    public List<JsonObject> getOnTimeout() {
        return onTimeout;
    }
}
