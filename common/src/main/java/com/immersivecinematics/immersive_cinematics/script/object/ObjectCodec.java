package com.immersivecinematics.immersive_cinematics.script.object;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象值的严格 JSON 转换器。
 *
 * <p>不做类型转换、不做别名：字符串数字不转 number，枚举不做模糊匹配，非有限数一律拒绝。
 * 失败抛 {@link ObjectFormatException}，文案与 parser / validator 的 {@code <path>: 需要…} 口径一致。</p>
 *
 * <p>事件记录形状（由路径末段决定）：
 * <ul>
 *   <li>{@code event}：{@code command}/{@code event_type}/{@code id}（字符串）、
 *       {@code position}（对象，恰好一组 {@code {x,z}} 或 {@code {dx,dz}}，数字有限）；</li>
 *   <li>{@code mod_event}：{@code event_type}（字符串）、{@code data}（对象，递归转
 *       {@code Map}/{@code List}/{@code String}/{@code Boolean}/{@code Float}/{@code null}）。</li>
 * </ul>
 * 返回值是不可变 {@code Map<String,Object>}。</p>
 */
public final class ObjectCodec {

    private ObjectCodec() {
    }

    /**
     * 按类型严格转换 JSON 值。
     *
     * @param type 目标值类型
     * @param json JSON 值；{@code null} 等价 JSON null
     * @param path 字段路径（用于错误信息，如 {@code position.x.value}）
     * @return 转换后的不可变值；{@code nullable_float} 的 {@code null} 合法
     * @throws ObjectFormatException 类型/形状/数值不合法
     */
    public static Object fromJson(PropertyType type, JsonElement json, String path) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }
        return switch (type) {
            case FLOAT -> readFiniteFloat(json, path, "需要有限数字");
            case INT -> readInt(json, path);
            case BOOL -> readBoolean(json, path);
            case STRING, REF, ENUM -> readString(json, path);
            case NULLABLE_FLOAT -> json == null || json.isJsonNull()
                    ? null
                    : readFiniteFloat(json, path, "需要 null 或有限数字");
            case EVENT_RECORD -> readEventRecord(json, path);
            case STRUCT -> readData(json, path);
        };
    }

    private static Float readFiniteFloat(JsonElement json, String path, String message) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
            throw new ObjectFormatException(path, message);
        }
        double number = json.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new ObjectFormatException(path, message);
        }
        float result = (float) number;
        if (!Float.isFinite(result)) {
            throw new ObjectFormatException(path, message);
        }
        return result;
    }

    private static Integer readInt(JsonElement json, String path) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
            throw new ObjectFormatException(path, "需要整数");
        }
        double number = json.getAsDouble();
        if (!Double.isFinite(number) || number != Math.floor(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ObjectFormatException(path, "需要整数");
        }
        return (int) number;
    }

    private static Boolean readBoolean(JsonElement json, String path) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean()) {
            throw new ObjectFormatException(path, "需要布尔值");
        }
        return json.getAsBoolean();
    }

    private static String readString(JsonElement json, String path) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
            throw new ObjectFormatException(path, "需要字符串");
        }
        return json.getAsString();
    }

    private static Map<String, Object> readEventRecord(JsonElement json, String path) {
        if (json == null || !json.isJsonObject()) {
            throw new ObjectFormatException(path, "需要对象（事件记录）");
        }
        String suffix = lastSegment(path);
        if ("event".equals(suffix)) {
            return readEvent(json.getAsJsonObject(), path);
        }
        if ("mod_event".equals(suffix)) {
            return readModEvent(json.getAsJsonObject(), path);
        }
        throw new ObjectFormatException(path, "未知事件记录路径（需要以 event / mod_event 结尾）");
    }

    private static Map<String, Object> readEvent(JsonObject object, String path) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            String keyPath = path + "." + key;
            switch (key) {
                case "command", "event_type", "id" -> record.put(key, readString(entry.getValue(), keyPath));
                case "position" -> record.put(key, readPosition(entry.getValue(), keyPath));
                default -> throw new ObjectFormatException(path, "未知成员 " + key);
            }
        }
        return Collections.unmodifiableMap(record);
    }

    private static Map<String, Object> readPosition(JsonElement json, String path) {
        if (json == null || !json.isJsonObject()) {
            throw new ObjectFormatException(path, "需要对象 {x,z} 或 {dx,dz}");
        }
        JsonObject object = json.getAsJsonObject();
        boolean coordinates = object.has("x") && object.has("z");
        boolean deltas = object.has("dx") && object.has("dz");
        if (object.size() != 2 || coordinates == deltas) {
            throw new ObjectFormatException(path, "需要对象 {x,z} 或 {dx,dz}");
        }
        Map<String, Object> position = new LinkedHashMap<>(4);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            position.put(entry.getKey(), readFiniteFloat(entry.getValue(), path + "." + entry.getKey(), "需要有限数字"));
        }
        return Collections.unmodifiableMap(position);
    }

    private static Map<String, Object> readModEvent(JsonObject object, String path) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            String keyPath = path + "." + key;
            switch (key) {
                case "event_type" -> record.put(key, readString(entry.getValue(), keyPath));
                case "data" -> {
                    if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                        throw new ObjectFormatException(keyPath, "需要对象");
                    }
                    record.put(key, readData(entry.getValue(), keyPath));
                }
                default -> throw new ObjectFormatException(path, "未知成员 " + key);
            }
        }
        return Collections.unmodifiableMap(record);
    }

    private static Object readData(JsonElement json, String path) {
        if (json == null || json.isJsonNull()) {
            return null;
        }
        if (json.isJsonArray()) {
            List<Object> list = new ArrayList<>(json.getAsJsonArray().size());
            for (int i = 0; i < json.getAsJsonArray().size(); i++) {
                list.add(readData(json.getAsJsonArray().get(i), path + "[" + i + "]"));
            }
            return Collections.unmodifiableList(list);
        }
        if (json.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), readData(entry.getValue(), path + "." + entry.getKey()));
            }
            return Collections.unmodifiableMap(map);
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return readFiniteFloat(primitive, path, "需要有限数字");
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        throw new ObjectFormatException(path, "不支持的 JSON 值");
    }

    private static String lastSegment(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
