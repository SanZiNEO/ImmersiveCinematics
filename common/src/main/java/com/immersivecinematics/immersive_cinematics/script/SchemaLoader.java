package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaRegistry;
import com.immersivecinematics.immersive_cinematics.script.schema.TrackTypeSchema;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 字段定义查询入口（0.3.5 第5轮 5B）。
 * <p>
 * 对外接口保持不变；内部已从 schema.json 迁移为 {@link SchemaRegistry} 的 Java 元数据。
 */
public final class SchemaLoader {

    private SchemaLoader() {}

    public static TrackTypeSchema get(TrackType type) {
        return SchemaRegistry.get(type);
    }

    public static Object getDefaultValue(TrackType type, boolean isKeyframe, String fieldName) {
        return SchemaRegistry.getDefaultValue(type, isKeyframe, fieldName);
    }

    public static boolean isRequired(TrackType type, boolean isKeyframe, String fieldName) {
        return SchemaRegistry.isRequired(type, isKeyframe, fieldName);
    }

    public static boolean hasField(TrackType type, boolean isKeyframe, String fieldName) {
        return SchemaRegistry.hasField(type, isKeyframe, fieldName);
    }

    public static Map<String, FieldDef> getClipFields(TrackType type) {
        return SchemaRegistry.getClipFields(type);
    }

    public static Map<String, FieldDef> getKeyframeFields(TrackType type) {
        return SchemaRegistry.getKeyframeFields(type);
    }

    public static List<String> getEnumValues(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = isKeyframe ? getKeyframeFields(type).get(fieldName) : getClipFields(type).get(fieldName);
        return def != null ? def.enumValues() : Collections.emptyList();
    }

    public static Map<String, FieldDef> getMetaFields() {
        return SchemaRegistry.getMetaFields();
    }
}
