package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import java.util.Map;

/**
 * Java 字段元数据统一入口（0.3.5 第5轮 5B）。
 * <p>
 * SchemaLoader 对外接口保持不变，内部改从这里读取，替代 schema.json。
 */
public final class SchemaRegistry {

    private static final Map<TrackType, TrackTypeSchema> TRACKS = TrackSchemas.all();
    private static final Map<String, FieldDef> META = MetaSchemas.all();

    private SchemaRegistry() {}

    public static TrackTypeSchema get(TrackType type) {
        TrackTypeSchema schema = TRACKS.get(type);
        return schema != null ? schema : TrackTypeSchema.EMPTY;
    }

    public static Map<String, FieldDef> getClipFields(TrackType type) {
        return get(type).clipFields();
    }

    public static Map<String, FieldDef> getKeyframeFields(TrackType type) {
        return get(type).kfFields();
    }

    public static Map<String, FieldDef> getMetaFields() {
        return META;
    }

    public static boolean hasField(TrackType type, boolean isKeyframe, String fieldName) {
        return isKeyframe ? getKeyframeFields(type).containsKey(fieldName) : getClipFields(type).containsKey(fieldName);
    }

    public static FieldDef getField(TrackType type, boolean isKeyframe, String fieldName) {
        return isKeyframe ? getKeyframeFields(type).get(fieldName) : getClipFields(type).get(fieldName);
    }

    public static Object getDefaultValue(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = getField(type, isKeyframe, fieldName);
        return def != null ? def.defaultValue() : null;
    }

    public static boolean isRequired(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = getField(type, isKeyframe, fieldName);
        return def != null && def.required();
    }
}
