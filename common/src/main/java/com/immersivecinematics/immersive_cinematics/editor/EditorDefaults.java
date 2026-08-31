package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.script.SchemaLoader;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;

import java.util.Map;

/**
 * 编辑器默认值工具——由 SchemaRegistry Java 元数据单源驱动（C3/C5 生成自适应）。
 * <p>
 * 替代原 LeftPanelArea.fillMetaDefaults/fillClipDefaults/fillKeyframeDefaults 与
 * EditorOperations.addClip 中的硬编码字段镜像。meta 的 tristate 字段无默认值，
 * 保持"不写入"（null = 三态未设置，与运行时 optNullableBool 语义一致）。
 */
public final class EditorDefaults {

    private EditorDefaults() {}

    /** meta 字段按 schema 的 "meta" 段补齐（tristate/无默认字段不写） */
    public static void fillMetaDefaults(JsonObject meta) {
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getMetaFields().entrySet()) {
            String key = e.getKey();
            if (meta.has(key)) continue;
            Object def = e.getValue().defaultValue();
            if (def != null) applyDefault(meta, key, def);
        }
    }

    /** 按 schema 补齐 clip 缺失字段；无默认值字段跳过；required 无默认的 string 字段补 ""（与现状一致） */
    public static void fillClipDefaults(JsonObject clip, String trackType) {
        TrackType tt = TrackType.valueOf(trackType.toUpperCase());
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getClipFields(tt).entrySet()) {
            String key = e.getKey();
            if (clip.has(key)) continue;
            Object def = e.getValue().defaultValue();
            if (def != null) {
                applyDefault(clip, key, def);
            } else if (e.getValue().required() && "string".equals(e.getValue().type())) {
                clip.addProperty(key, "");
            }
        }
    }

    /** 按 schema 补齐 keyframe 缺失字段；CAMERA position 特例保留（无 default 的 position → {dx,dy,dz}=0） */
    public static void fillKeyframeDefaults(JsonObject kf, String trackType) {
        TrackType tt = TrackType.valueOf(trackType.toUpperCase());
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getKeyframeFields(tt).entrySet()) {
            String key = e.getKey();
            if (kf.has(key)) continue;
            Object def = e.getValue().defaultValue();
            if (def != null) {
                applyDefault(kf, key, def);
            } else if ("position".equals(e.getValue().type()) && tt == TrackType.CAMERA) {
                JsonObject pos = new JsonObject();
                pos.addProperty("dx", 0f);
                pos.addProperty("dy", 2f);
                pos.addProperty("dz", 0f);
                kf.add("position", pos);
            }
        }
    }

    private static void applyDefault(JsonObject obj, String key, Object def) {
        if (def instanceof Boolean) obj.addProperty(key, (Boolean) def);
        else if (def instanceof Number) obj.addProperty(key, ((Number) def).floatValue());
        else if (def instanceof String) obj.addProperty(key, (String) def);
        else obj.add(key, JsonParser.parseString(def.toString()));
    }
}
