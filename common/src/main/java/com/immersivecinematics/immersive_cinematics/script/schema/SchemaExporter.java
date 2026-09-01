package com.immersivecinematics.immersive_cinematics.script.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.Map;

/**
 * 将 Java 字段元数据导出为标准 JSON schema（0.3.5 收尾 / WebUI 预留）。
 * <p>
 * 以后 WebUI 连接后通过 {@code schema.get} 获取这份数据，由前端动态生成表单；
 * Java 侧始终是唯一 schema 权威，避免两端字段漂移。
 */
public final class SchemaExporter {

    private SchemaExporter() {}

    public static JsonObject exportAll() {
        JsonObject root = new JsonObject();
        root.add("meta", exportFields(SchemaRegistry.getMetaFields()));

        JsonObject tracks = new JsonObject();
        for (TrackType type : TrackType.values()) {
            TrackTypeSchema schema = SchemaRegistry.get(type);
            JsonObject track = new JsonObject();
            track.add("clips", exportFields(schema.clipFields()));
            track.add("keyframes", exportFields(schema.kfFields()));
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
}
