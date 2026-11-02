package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import java.util.Map;

/**
 * Java 字段元数据统一入口。
 *
 * SchemaLoader 对外接口保持不变，内部从这里读取 Java 字段元数据。
 */
public final class SchemaRegistry {

    private static final Map<TrackType, TrackTypeSchema> TRACKS = TrackSchemas.all();
    private static final Map<String, FieldDef> META = MetaSchemas.all();

    static {
        ObjectSchemaValidator.validateOrThrow(TRACKS);
    }

    private SchemaRegistry() {}

    /** 取轨道类型的字段表；未登记的类型返回 {@link TrackTypeSchema#EMPTY}。 */
    public static TrackTypeSchema get(TrackType type) {
        TrackTypeSchema schema = TRACKS.get(type);
        return schema != null ? schema : TrackTypeSchema.EMPTY;
    }

    /** 取轨道类型的片段级字段表。 */
    public static Map<String, FieldDef> getClipFields(TrackType type) {
        return get(type).clipFields();
    }

    /** 取轨道类型的关键帧字段表。 */
    public static Map<String, FieldDef> getKeyframeFields(TrackType type) {
        return get(type).kfFields();
    }

    /** 取轨道类型的对象声明表（路径 → 声明）。 */
    public static Map<String, ObjectDef> getObjects(TrackType type) {
        return get(type).objects();
    }

    /** 取对象声明；未登记返回 null。 */
    public static ObjectDef getObject(TrackType type, String path) {
        return path == null ? null : getObjects(type).get(path);
    }

    /** 判定对象路径是否已登记。 */
    public static boolean hasObject(TrackType type, String path) {
        return getObject(type, path) != null;
    }

    /** 取对象缺省值；对象未登记或没有缺省返回 null。 */
    public static Object getObjectDefault(TrackType type, String path) {
        ObjectDef def = getObject(type, path);
        return def != null ? def.defaultValue() : null;
    }

    /** 取轨道类型的播放控制字段表。 */
    public static Map<String, FieldDef> getPlayControl(TrackType type) {
        return get(type).playControl();
    }

    /** 取脚本 meta 字段表（只读共享，调用方不得修改）。 */
    public static Map<String, FieldDef> getMetaFields() {
        return META;
    }

    /** 判定片段级 / 关键帧字段是否已登记。 */
    public static boolean hasField(TrackType type, boolean isKeyframe, String fieldName) {
        return isKeyframe ? getKeyframeFields(type).containsKey(fieldName) : getClipFields(type).containsKey(fieldName);
    }

    /** 取字段定义；未登记返回 null。 */
    public static FieldDef getField(TrackType type, boolean isKeyframe, String fieldName) {
        return isKeyframe ? getKeyframeFields(type).get(fieldName) : getClipFields(type).get(fieldName);
    }

    /** 取字段缺省值；字段未登记返回 null。 */
    public static Object getDefaultValue(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = getField(type, isKeyframe, fieldName);
        return def != null ? def.defaultValue() : null;
    }

    /** 判定字段是否必填；字段未登记视为非必填。 */
    public static boolean isRequired(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = getField(type, isKeyframe, fieldName);
        return def != null && def.required();
    }
}
