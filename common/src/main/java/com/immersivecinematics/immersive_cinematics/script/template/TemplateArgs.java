package com.immersivecinematics.immersive_cinematics.script.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次模板展开的实参：用户填写的值（可缺省）+ 解析后的最终值。
 *
 * <p>构造时按参数声明的 {@link TemplateParam#type()} 做类型归一（命令行的字符串 → 数值 / 布尔 / 枚举），
 * 未填写的参数取 {@link TemplateParam#defaultValue()}。因此展开器可以无条件读取
 * {@link #getFloat(String)} / {@link #getString(String)} 等，不需要自己兜默认值。</p>
 *
 * <p>{@link #startTime()} 不是模板参数，而是"这段 clip 放在时间轴哪一刻"——由调用方
 * （命令 / 编辑器插入点）给定，展开器把它写进产物的 {@code start_time}。</p>
 */
public final class TemplateArgs {

    private final Map<String, Object> values;
    private final float startTime;

    private TemplateArgs(Map<String, Object> values, float startTime) {
        this.values = Collections.unmodifiableMap(values);
        this.startTime = startTime;
    }

    /** 全部取默认值（命令行只给模板 id、不给参数时用）。 */
    public static TemplateArgs defaults(List<TemplateParam> params, float startTime) {
        return parse(params, Collections.emptyMap(), startTime);
    }

    /**
     * 解析实参。
     *
     * @param params    模板参数声明（决定类型归一与默认值）
     * @param raw       用户填写的值，key = 参数 key；值可以是字符串（命令行）或已归一的 Number / Boolean
     * @param startTime 该 clip 在时间轴上的起始时间（秒）
     * @throws IllegalArgumentException 未知参数 / 值无法归一 / 枚举值不在候选中
     */
    public static TemplateArgs parse(List<TemplateParam> params, Map<String, ?> raw, float startTime) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, ?> e : raw.entrySet()) {
            boolean declared = false;
            for (TemplateParam p : params) {
                if (p.key().equals(e.getKey())) {
                    declared = true;
                    break;
                }
            }
            if (!declared) unknown.add(e.getKey());
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("未知模板参数: " + String.join(", ", unknown)
                    + "（可用: " + String.join(", ", keys(params)) + "）");
        }
        for (TemplateParam p : params) {
            Object value = raw.containsKey(p.key()) ? raw.get(p.key()) : p.defaultValue();
            resolved.put(p.key(), coerce(p, value));
        }
        return new TemplateArgs(resolved, startTime);
    }

    private static List<String> keys(List<TemplateParam> params) {
        List<String> out = new ArrayList<>(params.size());
        for (TemplateParam p : params) out.add(p.key());
        return out;
    }

    /** 按声明类型归一：字符串 → 数值 / 布尔 / 枚举；已是 Number / Boolean 的按声明类型转换。 */
    private static Object coerce(TemplateParam p, Object value) {
        String key = p.key();
        if (value == null) {
            throw new IllegalArgumentException("模板参数 " + key + " 的值为空（类型 " + p.type() + "）");
        }
        try {
            return switch (p.type()) {
                case "float" -> value instanceof Number n ? n.floatValue() : Float.parseFloat(value.toString().trim());
                case "int" -> value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
                case "bool" -> value instanceof Boolean b ? b : parseBool(key, value.toString().trim());
                case "enum" -> {
                    String s = value.toString().trim();
                    for (String allowed : p.enumValues()) {
                        if (allowed.equals(s)) yield allowed;
                    }
                    throw new IllegalArgumentException("模板参数 " + key + " 的值 '" + s + "' 非法（可选: "
                            + String.join(" / ", p.enumValues()) + "）");
                }
                default -> value.toString();
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("模板参数 " + key + " 的值 '" + value + "' 不是合法的 "
                    + p.type() + "（默认 " + p.defaultValue() + "）");
        }
    }

    private static boolean parseBool(String key, String s) {
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw new IllegalArgumentException("模板参数 " + key + " 的值 '" + s + "' 不是布尔（true / false）");
    }

    // ── 展开器读取入口（值已归一，永不返回 null）──

    public float getFloat(String key) {
        Object v = values.get(key);
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    public int getInt(String key) {
        Object v = values.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    public boolean getBool(String key) {
        return values.get(key) instanceof Boolean b && b;
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v != null ? v.toString() : "";
    }

    /** 该 clip 在时间轴上的起始时间（秒）。 */
    public float startTime() {
        return startTime;
    }

    /** 全部参数 key → 归一后的值（含默认值），供调试 / 回显。 */
    public Map<String, Object> values() {
        return values;
    }
}
