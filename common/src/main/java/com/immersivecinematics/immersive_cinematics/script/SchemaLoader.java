package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 {@code schema.json} 并提供字段定义查询。
 * <p>
 * 为每种轨道类型提供 clip 字段和 keyframe 字段的元数据（类型、默认值、是否必填）。
 */
public final class SchemaLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SCHEMA_PATH = "/schema.json";

    private static Map<TrackType, TrackTypeSchema> cache = null;

    /** C1：schema.json 顶层 "meta" 段的字段定义（保序；编辑器 meta 面板数据源） */
    private static Map<String, FieldDef> metaFields = Collections.emptyMap();

    private SchemaLoader() {}

    /**
     * 获取指定轨道类型的 schema
     */
    public static TrackTypeSchema get(TrackType type) {
        if (cache == null) {
            load();
        }
        TrackTypeSchema schema = cache.get(type);
        if (schema == null) {
            LOGGER.warn("SchemaLoader: 未找到轨道类型 {} 的 schema", type);
            return TrackTypeSchema.EMPTY;
        }
        return schema;
    }

    private static void load() {
        cache = new LinkedHashMap<>();
        try (InputStream in = SchemaLoader.class.getResourceAsStream(SCHEMA_PATH)) {
            if (in == null) {
                LOGGER.error("SchemaLoader: 找不到 {}", SCHEMA_PATH);
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            JsonObject types = root.getAsJsonObject("track_types");
            for (Map.Entry<String, JsonElement> entry : types.entrySet()) {
                TrackType type;
                try {
                    type = TrackType.valueOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("SchemaLoader: 未知轨道类型 {}", entry.getKey());
                    continue;
                }
                JsonObject obj = entry.getValue().getAsJsonObject();
                Map<String, FieldDef> clipFields = parseFieldDefs(obj.getAsJsonObject("clips"));
                Map<String, FieldDef> kfFields = parseFieldDefs(obj.getAsJsonObject("keyframes"));
                cache.put(type, new TrackTypeSchema(clipFields, kfFields));
            }
            // C1：meta 段（编辑器生成自适应数据源；运行时 ScriptParser 不读）
            if (root.has("meta") && root.get("meta").isJsonObject()) {
                metaFields = parseFieldDefs(root.getAsJsonObject("meta"));
            }
        } catch (Exception e) {
            LOGGER.error("SchemaLoader: 加载失败", e);
        }
    }

    private static Map<String, FieldDef> parseFieldDefs(JsonObject obj) {
        if (obj == null) return Collections.emptyMap();
        Map<String, FieldDef> map = new LinkedHashMap<>();  // 保序（编辑器按 schema 顺序渲染）
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonObject def = e.getValue().getAsJsonObject();
            String fieldType = def.get("type").getAsString();
            Object defaultValue = null;
            if (def.has("default")) {
                JsonElement dv = def.get("default");
                if (dv.isJsonPrimitive()) {
                    if (dv.getAsJsonPrimitive().isNumber()) {
                        defaultValue = dv.getAsFloat();
                    } else if (dv.getAsJsonPrimitive().isBoolean()) {
                        defaultValue = dv.getAsBoolean();
                    } else {
                        defaultValue = dv.getAsString();
                    }
                }
            }
            boolean required = def.has("required") && def.get("required").getAsBoolean();
            // enum values（C1：编辑器枚举循环驱动）
            List<String> enumValues = Collections.emptyList();
            if (def.has("values") && def.get("values").isJsonArray()) {
                List<String> vals = new ArrayList<>();
                for (JsonElement ve : def.getAsJsonArray("values")) {
                    vals.add(ve.getAsString());
                }
                enumValues = vals;
            }
            // section（仅 meta 段使用：info/runtime 分组）
            String section = def.has("section") ? def.get("section").getAsString() : "info";
            map.put(e.getKey(), new FieldDef(fieldType, defaultValue, required, enumValues, section));
        }
        return map;
    }

    /**
     * 获取字段的默认值
     */
    public static Object getDefaultValue(TrackType type, boolean isKeyframe, String fieldName) {
        TrackTypeSchema schema = get(type);
        FieldDef def = isKeyframe ? schema.kfFields.get(fieldName) : schema.clipFields.get(fieldName);
        return def != null ? def.defaultValue : null;
    }

    /**
     * 判断字段是否必填
     */
    public static boolean isRequired(TrackType type, boolean isKeyframe, String fieldName) {
        TrackTypeSchema schema = get(type);
        FieldDef def = isKeyframe ? schema.kfFields.get(fieldName) : schema.clipFields.get(fieldName);
        return def != null && def.required;
    }

    /**
     * 判断字段是否存在
     */
    public static boolean hasField(TrackType type, boolean isKeyframe, String fieldName) {
        TrackTypeSchema schema = get(type);
        return isKeyframe ? schema.kfFields.containsKey(fieldName) : schema.clipFields.containsKey(fieldName);
    }

    /**
     * 获取指定轨道的 clip 字段定义
     */
    public static Map<String, FieldDef> getClipFields(TrackType type) {
        return get(type).clipFields;
    }

    /**
     * 获取指定轨道的 keyframe 字段定义
     */
    public static Map<String, FieldDef> getKeyframeFields(TrackType type) {
        return get(type).kfFields;
    }

    /** C1：字段的枚举候选值；非 enum 字段返回空列表 */
    public static List<String> getEnumValues(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = isKeyframe ? getKeyframeFields(type).get(fieldName) : getClipFields(type).get(fieldName);
        return def != null ? def.enumValues() : Collections.emptyList();
    }

    /** C1：meta 字段定义（保序；schema.json 顶层 "meta" 段；无 meta 段时返回空 map） */
    public static Map<String, FieldDef> getMetaFields() {
        if (cache == null) load();
        return metaFields;
    }

    public record FieldDef(String type, Object defaultValue, boolean required,
                           List<String> enumValues, String section) {}

    public record TrackTypeSchema(Map<String, FieldDef> clipFields, Map<String, FieldDef> kfFields) {
        static final TrackTypeSchema EMPTY = new TrackTypeSchema(Collections.emptyMap(), Collections.emptyMap());
    }
}
