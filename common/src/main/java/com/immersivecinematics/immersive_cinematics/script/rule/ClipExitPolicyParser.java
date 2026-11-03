package com.immersivecinematics.immersive_cinematics.script.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitKind;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitPolicy;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipResolver;

import java.util.List;

/**
 * 出口策略解析：{@code exit} 对象 → {@link ClipExitPolicy}（片段默认出口与绑定入口覆盖共用）。
 *
 * <pre>
 * {"on_complete":"continue"|"return"|"end_execution"|"end_script"}
 * {"on_complete":"goto","target":"&lt;clipId&gt;","entry_time":0.0}
 * </pre>
 *
 * <p>校验口径（{@code ROUND-I-4-MAP} §1.5）：{@code on_complete} 必填枚举；{@code goto} 必须带
 * {@code target}（解析为片段）且 {@code entry_time} 可省（缺省 0，有限且 {@code >= 0}）；
 * 其余类型禁止 {@code target} / {@code entry_time}；未知键一律报错。</p>
 */
public final class ClipExitPolicyParser {

    /** 出口键白名单。 */
    public static final List<String> KEYS = List.of("on_complete", "target", "entry_time");

    /** 出口类型写法（顺序 = {@link ClipExitKind} 声明顺序）。 */
    public static final List<String> KIND_NAMES = List.of(
            "continue", "goto", "return", "end_execution", "end_script");

    private ClipExitPolicyParser() {}

    /**
     * 解析出口声明。
     *
     * @param exit    {@code exit} 对象
     * @param clips   片段解析器
     * @param path    报错用 JSON 路径
     * @return 出口策略（永不为 {@code null}）
     * @throws ScriptParseException 结构非法 / 未知片段引用
     */
    public static ClipExitPolicy parse(JsonObject exit, ClipResolver clips, String path)
            throws ScriptParseException {
        rejectUnknownKeys(exit, path);
        String kindName = requireString(exit, path, "on_complete");
        ClipExitKind kind = kindOf(kindName);
        if (kind == null) {
            throw new ScriptParseException(path + ".on_complete", "未知出口类型: " + kindName
                    + "（可选: " + String.join(" / ", KIND_NAMES) + "）");
        }
        if (kind == ClipExitKind.GOTO) {
            String targetId = requireString(exit, path, "target");
            Clip target = clips.resolve(targetId);
            if (target == null) {
                throw new ScriptParseException(path + ".target", "未知片段引用: " + targetId);
            }
            double entryTime = exit.has("entry_time") ? requireEntryTime(exit, path) : 0.0;
            return new ClipExitPolicy(kind, target, entryTime);
        }
        if (exit.has("target")) {
            throw new ScriptParseException(path + ".target", kindName + " 不允许带 target（只有 goto 需要）");
        }
        if (exit.has("entry_time")) {
            throw new ScriptParseException(path + ".entry_time",
                    kindName + " 不允许带 entry_time（只有 goto 需要）");
        }
        return new ClipExitPolicy(kind, null, 0.0);
    }

    /** 按脚本写法解析出口类型；未知返回 {@code null}。 */
    public static ClipExitKind kindOf(String name) {
        if (name == null) return null;
        return switch (name) {
            case "continue" -> ClipExitKind.CONTINUE;
            case "goto" -> ClipExitKind.GOTO;
            case "return" -> ClipExitKind.RETURN;
            case "end_execution" -> ClipExitKind.END_EXECUTION;
            case "end_script" -> ClipExitKind.END_SCRIPT;
            default -> null;
        };
    }

    /** 出口键白名单校验（校验期复用同一套结构规则）。 */
    public static void rejectUnknownKeys(JsonObject exit, String path) throws ScriptParseException {
        for (String key : exit.keySet()) {
            if (!KEYS.contains(key)) {
                throw new ScriptParseException(path + "." + key,
                        "未知键（exit 可用: " + String.join(" / ", KEYS) + "）");
            }
        }
    }

    private static double requireEntryTime(JsonObject exit, String path) throws ScriptParseException {
        JsonElement e = exit.get("entry_time");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(path + ".entry_time", "需要数字（入口局部秒，>= 0）");
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value) || value < 0.0) {
            throw new ScriptParseException(path + ".entry_time", "需要 >= 0 的有限数字，实际: " + e);
        }
        return value;
    }

    private static String requireString(JsonObject obj, String path, String key) throws ScriptParseException {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()
                || e.getAsString().isEmpty()) {
            throw new ScriptParseException(path + "." + key, "缺失或不是非空字符串");
        }
        return e.getAsString();
    }
}
