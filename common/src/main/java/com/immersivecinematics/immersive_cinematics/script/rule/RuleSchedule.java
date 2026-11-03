package com.immersivecinematics.immersive_cinematics.script.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipResolver;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 动作调度（绑定级 {@code schedule}）：{@link RuleRelease#kind()} = 释放条件，{@code null} = 立即提交。
 *
 * <pre>
 * 缺省                                  判定成功后立即提交
 * {"on":"clip_start","clip":"c2"}       目标片段下一次执行开始时提交
 * {"on":"clip_complete","clip":"c2"}    目标片段执行完成时提交
 * {"on":"local_time","clip":"c2","at":1} 目标片段执行到达局部时间时提交
 * {"on":"delay","seconds":0.5}          判定成功后经过真实秒数提交（挂起不冻结）
 * {"on":"fact","binding":"b1","fact":"succeeded"|"action_completed"}
 * </pre>
 *
 * <p>解析即校验：{@code on} 必填、枚举；各形态参数必填且类型正确；未知键 / 多余键一律报错（带路径）。
 * {@code fact} 形态需要绑定表上下文，见 {@link #parse(JsonObject, ClipResolver, Function, String)}。</p>
 */
public record RuleSchedule(RuleRelease release) {

    /** 立即提交（无 {@code schedule} 字段时的缺省形态）。 */
    public static final RuleSchedule IMMEDIATE = new RuleSchedule(null);

    /** {@code local_time} 形态的局部时间键。 */
    public static final String KEY_ON = "on";

    /** 是否缺省（无调度，立即提交）。 */
    public boolean isImmediate() {
        return release == null;
    }

    /**
     * 解析 {@code schedule}：不支持 {@code fact} 形态（无绑定表可解析，未知引用）。
     *
     * @param obj    {@code schedule} 对象
     * @param clips  片段解析器
     * @param path   报错用 JSON 路径
     * @return 解析后的调度（永不为 {@code null}）
     * @throws ScriptParseException 结构非法
     */
    public static RuleSchedule parse(JsonObject obj, ClipResolver clips, String path)
            throws ScriptParseException {
        return parse(obj, clips, id -> null, path);
    }

    /**
     * 解析 {@code schedule}。
     *
     * @param obj     {@code schedule} 对象
     * @param clips   片段解析器
     * @param bindings 绑定 id → 绑定（{@code fact} 形态用；未知返回 {@code null}）
     * @param path    报错用 JSON 路径
     * @return 解析后的调度（永不为 {@code null}）
     * @throws ScriptParseException 结构非法 / 未知引用
     */
    public static RuleSchedule parse(JsonObject obj, ClipResolver clips,
                                     Function<String, RuleBinding> bindings, String path)
            throws ScriptParseException {
        Spec spec = read(obj, clips, null, path);
        if (spec.kind != RuleReleaseKind.FACT) {
            return new RuleSchedule(toRelease(spec, null));
        }
        RuleBinding source = bindings.apply(spec.bindingId);
        if (source == null) {
            throw new ScriptParseException(path + ".binding", "未知绑定引用: " + spec.bindingId);
        }
        return new RuleSchedule(toRelease(spec, source));
    }

    /**
     * 只做结构校验（不构造 {@link RuleBinding} 引用），供校验期 / 两遍解析的第一遍使用。
     *
     * @param obj            {@code schedule} 对象
     * @param clips          片段解析器
     * @param knownBindingIds 已知绑定 id 集合（{@code null} = 不校验 fact 引用）
     * @param path           报错用 JSON 路径
     * @return {@code fact} 形态引用的绑定 id；其余形态 {@code null}
     * @throws ScriptParseException 结构非法 / 未知引用
     */
    public static String validate(JsonObject obj, ClipResolver clips, Set<String> knownBindingIds,
                                  String path) throws ScriptParseException {
        Spec spec = read(obj, clips, knownBindingIds, path);
        return spec.kind == RuleReleaseKind.FACT ? spec.bindingId : null;
    }

    /** 五种形态的合法键（报错文案用）。 */
    public static List<String> parameterNames(RuleReleaseKind kind) {
        return switch (kind) {
            case CLIP_START, CLIP_COMPLETE -> List.of("on", "clip");
            case LOCAL_TIME -> List.of("on", "clip", "at");
            case DELAY -> List.of("on", "seconds");
            case FACT -> List.of("on", "binding", "fact");
        };
    }

    // ========== 内部 ==========

    /** 解析中间形态：绑定引用仍是 id（第二遍才解析成对象）。 */
    private record Spec(RuleReleaseKind kind, Clip clip, double at, String bindingId, String fact) {}

    private static Spec read(JsonObject obj, ClipResolver clips, Set<String> knownBindingIds, String path)
            throws ScriptParseException {
        JsonElement onElement = obj.get(KEY_ON);
        if (onElement == null || !isString(onElement)) {
            throw new ScriptParseException(path + "." + KEY_ON,
                    "缺失或不是字符串（释放条件: " + String.join(" / ", RuleReleaseKind.SCRIPT_NAMES) + "）");
        }
        RuleReleaseKind kind = RuleReleaseKind.fromScriptName(onElement.getAsString());
        if (kind == null) {
            throw new ScriptParseException(path + "." + KEY_ON, "未知释放条件: " + onElement.getAsString()
                    + "（可选: " + String.join(" / ", RuleReleaseKind.SCRIPT_NAMES) + "）");
        }
        rejectUnknown(obj, path, kind);
        return switch (kind) {
            case CLIP_START -> new Spec(kind, requireClip(clips, obj, path, ".clip"), 0.0, null, null);
            case CLIP_COMPLETE -> new Spec(kind, requireClip(clips, obj, path, ".clip"), 0.0, null, null);
            case LOCAL_TIME -> new Spec(kind, requireClip(clips, obj, path, ".clip"),
                    requireNumber(obj, path, ".at", true), null, null);
            case DELAY -> new Spec(kind, null, requireNumber(obj, path, ".seconds", true), null, null);
            case FACT -> {
                String binding = requireString(obj, path, ".binding");
                String fact = requireString(obj, path, ".fact");
                if (!FACT_NAMES.contains(fact)) {
                    throw new ScriptParseException(path + ".fact", "需要 succeeded / action_completed，实际: " + fact);
                }
                if (knownBindingIds != null && !knownBindingIds.contains(binding)) {
                    throw new ScriptParseException(path + ".binding", "未知绑定引用: " + binding);
                }
                yield new Spec(kind, null, 0.0, binding, fact);
            }
        };
    }

    /** {@code fact} 形态的事实名（与条件树 {@code rule_fact} 同一套词汇）。 */
    public static final List<String> FACT_NAMES = List.of("succeeded", "action_completed");

    private static RuleRelease toRelease(Spec spec, RuleBinding source) {
        return switch (spec.kind) {
            case CLIP_START -> RuleRelease.clipStart(spec.clip);
            case CLIP_COMPLETE -> RuleRelease.clipComplete(spec.clip);
            case LOCAL_TIME -> RuleRelease.localTime(spec.clip, spec.at);
            case DELAY -> RuleRelease.delay(spec.at);
            case FACT -> RuleRelease.fact(source, spec.fact);
        };
    }

    private static void rejectUnknown(JsonObject obj, String path, RuleReleaseKind kind)
            throws ScriptParseException {
        List<String> allowed = parameterNames(kind);
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new ScriptParseException(path + "." + key, "未知键（" + kind.scriptName()
                        + " 可用: " + String.join(" / ", allowed) + "）");
            }
        }
    }

    private static Clip requireClip(ClipResolver clips, JsonObject obj, String path, String suffix)
            throws ScriptParseException {
        String id = requireString(obj, path, suffix);
        Clip clip = clips.resolve(id);
        if (clip == null) {
            throw new ScriptParseException(path + suffix, "未知片段引用: " + id);
        }
        return clip;
    }

    private static String requireString(JsonObject obj, String path, String suffix)
            throws ScriptParseException {
        String key = suffix.substring(1);
        JsonElement e = obj.get(key);
        if (e == null || !isString(e) || e.getAsString().isEmpty()) {
            throw new ScriptParseException(path + suffix, "缺失或不是非空字符串");
        }
        return e.getAsString();
    }

    private static double requireNumber(JsonObject obj, String path, String suffix, boolean nonNegative)
            throws ScriptParseException {
        String key = suffix.substring(1);
        JsonElement e = obj.get(key);
        if (e == null || !isNumber(e)) {
            throw new ScriptParseException(path + suffix, "缺失或不是数字");
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new ScriptParseException(path + suffix, "需要有限数字: " + e);
        }
        if (nonNegative && value < 0.0) {
            throw new ScriptParseException(path + suffix, "需要 >= 0，实际: " + value);
        }
        return value;
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }
}
