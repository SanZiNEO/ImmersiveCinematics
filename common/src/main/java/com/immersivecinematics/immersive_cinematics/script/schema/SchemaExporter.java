package com.immersivecinematics.immersive_cinematics.script.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.math.path.PathCapability;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * 将 Java 字段元数据导出为标准 JSON schema。
 *
 * 以后 WebUI 连接后通过 {@code schema.get} 获取这份数据，由前端动态生成表单；
 * Java 侧始终是唯一 schema 权威，避免两端字段漂移。
 */
public final class SchemaExporter {

    private SchemaExporter() {}

    /** 导出完整 schema（meta / tracks / triggers / models / curveTypes 五段）；只转发各注册表，不自行补字段。 */
    public static JsonObject exportAll() {
        JsonObject root = new JsonObject();
        root.add("meta", exportFields(SchemaRegistry.getMetaFields()));

        JsonObject tracks = new JsonObject();
        for (TrackType type : TrackType.values()) {
            TrackTypeSchema schema = SchemaRegistry.get(type);
            JsonObject track = new JsonObject();
            track.add("clips", exportFields(schema.clipFields()));
            track.add("keyframes", exportFields(schema.kfFields()));
            track.add("playControl", exportFields(schema.playControl()));
            track.add("objects", exportObjects(schema.objects()));
            tracks.add(type.name(), track);
        }
        root.add("tracks", tracks);

        // 触发器 schema：类型列表 + 每种类型的 conditions 字段
        JsonObject triggers = new JsonObject();
        JsonArray typeList = new JsonArray();
        for (String t : TriggerSchemas.typeList()) typeList.add(t);
        triggers.add("types", typeList);
        JsonObject conditions = new JsonObject();
        for (Map.Entry<String, Map<String, FieldDef>> e : TriggerSchemas.all().entrySet()) {
            conditions.add(e.getKey(), exportFields(e.getValue()));
        }
        triggers.add("conditions", conditions);
        root.add("triggers", triggers);

        // 数学模型 schema：type → 参数表（类型顺序 = 注册顺序；编辑器类型下拉 + 参数表单直接消费）
        JsonObject models = new JsonObject();
        for (String type : MathModelRegistry.types()) {
            models.add(type, exportFields(MathModelRegistry.params(type)));
        }
        root.add("models", models);

        // 路径曲线 schema：type →（capabilities + params）
        // 曲线类型顺序 = 注册顺序；编辑器类型下拉 + 参数表单直接消费
        JsonObject curveTypes = new JsonObject();
        for (String type : PathCurveRegistry.types()) {
            JsonObject curve = new JsonObject();
            JsonArray capabilities = new JsonArray();
            for (PathCapability capability : PathCurveRegistry.capabilities(type)) {
                capabilities.add(capability.name());
            }
            curve.add("capabilities", capabilities);
            JsonObject params = new JsonObject();
            for (PathParamDef def : PathCurveRegistry.params(type)) {
                JsonObject param = new JsonObject();
                param.addProperty("kind", def.kind().name());
                Object defVal = def.defaultValue();
                if (defVal == null) {
                    param.add("default", JsonNull.INSTANCE);
                } else if (defVal instanceof Boolean b) {
                    param.addProperty("default", b);
                } else if (defVal instanceof Number n) {
                    param.addProperty("default", n);
                } else {
                    param.addProperty("default", defVal.toString());
                }
                param.addProperty("required", def.required());
                JsonArray enumValues = new JsonArray();
                for (String value : def.enumValues()) {
                    enumValues.add(value);
                }
                param.add("enumValues", enumValues);
                param.addProperty("doc", def.doc());
                params.add(def.name(), param);
            }
            curve.add("params", params);
            curveTypes.add(type, curve);
        }
        root.add("curveTypes", curveTypes);

        return root;
    }

    private static JsonObject exportFields(Map<String, FieldDef> fields) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, FieldDef> e : fields.entrySet()) {
            out.add(e.getKey(), exportField(e.getValue()));
        }
        return out;
    }

    private static JsonObject exportField(FieldDef def) {
        JsonObject o = new JsonObject();
        o.addProperty("type", def.type());

        Object defVal = def.defaultValue();
        if (defVal == null) {
            o.add("default", JsonNull.INSTANCE);
        } else if (defVal instanceof Boolean b) {
            o.addProperty("default", b);
        } else if (defVal instanceof Number n) {
            o.addProperty("default", n);
        } else {
            o.addProperty("default", defVal.toString());
        }

        o.addProperty("required", def.required());

        JsonArray vals = new JsonArray();
        for (String v : def.enumValues()) vals.add(v);
        o.add("enumValues", vals);

        o.addProperty("section", def.section());
        return o;
    }

    private static JsonObject exportObjects(Map<String, ObjectDef> objects) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, ObjectDef> entry : objects.entrySet()) {
            out.add(entry.getKey(), exportObject(entry.getValue()));
        }
        return out;
    }

    private static JsonObject exportObject(ObjectDef def) {
        JsonObject o = new JsonObject();
        o.addProperty("path", def.path());
        o.addProperty("mode", scriptName(def.mode()));
        o.addProperty("type", scriptName(def.type()));
        o.addProperty("interp", scriptName(def.interp()));
        o.add("default", toJson(def.defaultValue()));
        o.addProperty("required", def.required());
        JsonArray enumValues = new JsonArray();
        for (String value : def.enumValues()) {
            enumValues.add(value);
        }
        o.add("enumValues", enumValues);
        o.addProperty("nullable", def.nullable());
        o.add("min", def.min() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.min()));
        o.add("max", def.max() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.max()));
        o.add("requires", def.requires() == null
                ? JsonNull.INSTANCE
                : new JsonPrimitive(def.requires()));
        o.add("section", def.section() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.section()));
        o.add("group", def.group() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.group()));
        JsonArray appliesTo = new JsonArray();
        for (String value : def.appliesTo()) {
            appliesTo.add(value);
        }
        o.add("appliesTo", appliesTo);
        o.add("labelKey", def.labelKey() == null
                ? JsonNull.INSTANCE
                : new JsonPrimitive(def.labelKey()));
        o.add("unit", def.unit() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.unit()));
        return o;
    }

    private static String scriptName(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object element : collection) {
                array.add(toJson(element));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
