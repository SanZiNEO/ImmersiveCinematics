package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.object.Interp;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyMode;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyPath;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对象声明的静态自检：路径合法性 / 唯一性、形态与类型兼容、缺省值类型、枚举、数值区间、
 * 可空约束与 {@code requires} 引用（存在 + 无环）。
 *
 * <p>只检查 schema 声明，不读脚本数据；返回中文 issue 列表，空表 = 通过。
 * {@link SchemaRegistry} 在静态初始化时调用 {@link #validateOrThrow(Map)}，声明错误在类加载期暴露。</p>
 */
public final class ObjectSchemaValidator {

    private ObjectSchemaValidator() {}

    /**
     * 校验全部轨道的对象声明。
     *
     * @param schemas 轨道类型 → schema；{@code null} 视为无声明
     * @return issue 列表（中文）；空 = 通过
     */
    public static List<String> validate(Map<TrackType, TrackTypeSchema> schemas) {
        List<String> issues = new ArrayList<>();
        if (schemas == null) {
            return issues;
        }
        for (Map.Entry<TrackType, TrackTypeSchema> entry : schemas.entrySet()) {
            validateTrack(entry.getKey(), entry.getValue(), issues);
        }
        return issues;
    }

    /**
     * 校验并在失败时抛异常。
     *
     * @param schemas 轨道类型 → schema
     * @throws IllegalStateException 任一声明不合法
     */
    public static void validateOrThrow(Map<TrackType, TrackTypeSchema> schemas) {
        List<String> issues = validate(schemas);
        if (!issues.isEmpty()) {
            throw new IllegalStateException("对象 schema 自检失败:\n" + String.join("\n", issues));
        }
    }

    private static void validateTrack(TrackType track, TrackTypeSchema schema, List<String> issues) {
        if (track == null || schema == null) {
            return;
        }
        String prefix = track.name() + ".objects";
        Map<String, ObjectDef> objects = schema.objects();
        Set<String> seen = new HashSet<>();
        Map<String, String> requiresEdges = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectDef> entry : objects.entrySet()) {
            String key = entry.getKey();
            ObjectDef def = entry.getValue();
            if (def == null) {
                issues.add(prefix + "[" + key + "]: 声明不能为 null");
                continue;
            }
            String where = prefix + "[" + def.path() + "]";
            if (key == null || !PropertyPath.isValid(key)) {
                issues.add(prefix + "[" + key + "]: 非法对象路径");
            } else if (!key.equals(def.path())) {
                issues.add(where + ": path 与键不一致（键 = " + key + "）");
            }
            if (def.path() == null || !PropertyPath.isValid(def.path())) {
                issues.add(prefix + "[" + key + "]: 非法对象路径");
            } else if (!seen.add(def.path())) {
                issues.add(where + ": 重复对象路径");
            }

            validateMode(where, def, issues);
            if (!defaultMatches(def.type(), def.defaultValue())) {
                issues.add(where + ": default 类型与 type=" + scriptName(def.type()) + " 不匹配");
            }
            if (def.type() == PropertyType.ENUM) {
                if (def.enumValues().isEmpty()) {
                    issues.add(where + ": type=enum 需要非空 enumValues");
                } else if (def.defaultValue() != null && !def.enumValues().contains(def.defaultValue())) {
                    issues.add(where + ": default 不在 enumValues 内");
                }
            }
            if (def.nullable() && def.type() != PropertyType.NULLABLE_FLOAT) {
                issues.add(where + ": nullable 仅允许 type=nullable_float");
            }
            validateRange(where, def, issues);

            String required = requiresPath(def.requires());
            if (def.requires() != null) {
                if (required == null || !PropertyPath.isValid(required)) {
                    issues.add(where + ": requires 格式非法（需要 <路径>=<值> 或 <路径>!=<值>）");
                } else if (!objects.containsKey(required)) {
                    issues.add(where + ": requires 引用未知路径 " + required);
                } else {
                    requiresEdges.put(def.path(), required);
                }
            }
        }
        reportCycles(prefix, requiresEdges, objects, issues);
    }

    private static void validateMode(String where, ObjectDef def, List<String> issues) {
        PropertyMode mode = def.mode();
        PropertyType type = def.type();
        if (mode == null || type == null) {
            return;
        }
        if (type == PropertyType.STRUCT && mode != PropertyMode.VALUE) {
            issues.add(where + ": struct 只允许 mode=value");
        }
        switch (mode) {
            case VALUE -> {
                if (def.interp() != Interp.NONE) {
                    issues.add(where + ": mode=value 时 interp 必须为 none");
                }
                if (type == PropertyType.EVENT_RECORD) {
                    issues.add(where + ": event_record 只能用于 mode=event");
                }
            }
            case ANIMATED -> {
                if (def.interp() == Interp.NONE) {
                    issues.add(where + ": mode=animated 需要非 none 的 interp");
                } else if (!def.interp().accepts(type)) {
                    issues.add(where + ": interp=" + scriptName(def.interp())
                            + " 不适用于 type=" + scriptName(type));
                }
                if (type == PropertyType.EVENT_RECORD) {
                    issues.add(where + ": event_record 只能用于 mode=event");
                }
            }
            case EVENT -> {
                if (type != PropertyType.EVENT_RECORD) {
                    issues.add(where + ": mode=event 的 type 必须是 event_record");
                }
                if (def.interp() != Interp.NONE) {
                    issues.add(where + ": mode=event 时 interp 必须为 none");
                }
            }
        }
    }

    private static void validateRange(String where, ObjectDef def, List<String> issues) {
        if (def.min() != null && !Double.isFinite(def.min())) {
            issues.add(where + ": min 必须为有限数字");
        }
        if (def.max() != null && !Double.isFinite(def.max())) {
            issues.add(where + ": max 必须为有限数字");
        }
        if (def.min() != null && def.max() != null && def.min() > def.max()) {
            issues.add(where + ": min 不能大于 max");
        }
    }

    private static boolean defaultMatches(PropertyType type, Object value) {
        if (value == null) {
            return true;
        }
        if (type == null) {
            return false;
        }
        return switch (type) {
            case FLOAT, NULLABLE_FLOAT -> value instanceof Number number && Float.isFinite(number.floatValue());
            case INT -> value instanceof Number number && isIntegral(number);
            case BOOL -> value instanceof Boolean;
            case STRING, ENUM, REF -> value instanceof String;
            case EVENT_RECORD -> value instanceof Map<?, ?>;
            case STRUCT -> isStructValue(value);
        };
    }

    private static boolean isIntegral(Number number) {
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.floor(value)
                && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private static boolean isStructValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return true;
        }
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) && Float.isFinite(number.floatValue());
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || !isStructValue(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (!isStructValue(element)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String requiresPath(String requires) {
        if (requires == null) {
            return null;
        }
        String text = requires.trim();
        int notEquals = text.indexOf("!=");
        if (notEquals > 0) {
            return text.substring(0, notEquals).trim();
        }
        int equals = text.indexOf('=');
        if (equals > 0) {
            return text.substring(0, equals).trim();
        }
        return null;
    }

    private static void reportCycles(String prefix, Map<String, String> edges,
                                     Map<String, ObjectDef> objects, List<String> issues) {
        Map<String, Integer> state = new HashMap<>();
        for (String start : edges.keySet()) {
            List<String> stack = new ArrayList<>();
            String current = start;
            while (current != null && edges.containsKey(current)) {
                Integer mark = state.get(current);
                if (mark != null && mark == 2) {
                    break;
                }
                int index = stack.indexOf(current);
                if (index >= 0) {
                    List<String> cycle = new ArrayList<>(stack.subList(index, stack.size()));
                    cycle.add(current);
                    issues.add(prefix + ": requires 环 " + String.join(" -> ", cycle));
                    break;
                }
                if (mark != null && mark == 1) {
                    break;
                }
                state.put(current, 1);
                stack.add(current);
                current = edges.get(current);
            }
            for (String path : stack) {
                state.put(path, 2);
            }
        }
    }

    private static String scriptName(Enum<?> value) {
        return value == null ? "null" : value.name().toLowerCase(java.util.Locale.ROOT);
    }
}
