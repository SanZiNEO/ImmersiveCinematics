package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.track.TrackRegistry;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.Map;

/**
 * Java 字段元数据统一入口。
 *
 * SchemaLoader 对外接口保持不变，内部从这里读取 Java 字段元数据；轨道表本体在
 * {@link TrackRegistry}（内置 7 轨 + 第三方注册），本类只转发。
 */
public final class SchemaRegistry {

    private static final Map<String, FieldDef> META = MetaSchemas.all();

    private SchemaRegistry() {}

    /** 取轨道类型的字段表；未登记的类型返回 {@link TrackTypeSchema#EMPTY}。 */
    public static TrackTypeSchema get(TrackTypeId type) {
        return TrackRegistry.schema(type);
    }

    /** 取轨道类型的片段级字段表。 */
    public static Map<String, FieldDef> getClipFields(TrackTypeId type) {
        return get(type).clipFields();
    }

    /** 取轨道类型的关键帧字段表。 */
    public static Map<String, FieldDef> getKeyframeFields(TrackTypeId type) {
        return get(type).kfFields();
    }

    /** 取轨道类型的对象声明表（路径 → 声明）。 */
    public static Map<String, ObjectDef> getObjects(TrackTypeId type) {
        return get(type).objects();
    }

    /** 取对象声明；未登记返回 null。 */
    public static ObjectDef getObject(TrackTypeId type, String path) {
        return path == null ? null : getObjects(type).get(path);
    }

    /** 判定对象路径是否已登记。 */
    public static boolean hasObject(TrackTypeId type, String path) {
        return getObject(type, path) != null;
    }

    /** 取对象缺省值；对象未登记或没有缺省返回 null。 */
    public static Object getObjectDefault(TrackTypeId type, String path) {
        ObjectDef def = getObject(type, path);
        return def != null ? def.defaultValue() : null;
    }

    /** 取轨道类型的播放控制字段表。 */
    public static Map<String, FieldDef> getPlayControl(TrackTypeId type) {
        return get(type).playControl();
    }

    /** 取脚本 meta 字段表（只读共享，调用方不得修改）。 */
    public static Map<String, FieldDef> getMetaFields() {
        return META;
    }

    /** 判定片段级 / 关键帧字段是否已登记。 */
    public static boolean hasField(TrackTypeId type, boolean isKeyframe, String fieldName) {
        return isKeyframe ? getKeyframeFields(type).containsKey(fieldName) : getClipFields(type).containsKey(fieldName);
    }

    /** 取字段定义；未登记返回 null。 */
    public static FieldDef getField(TrackTypeId type, boolean isKeyframe, String fieldName) {
        return isKeyframe ? getKeyframeFields(type).get(fieldName) : getClipFields(type).get(fieldName);
    }

    /** 取字段缺省值；字段未登记返回 null。 */
    public static Object getDefaultValue(TrackTypeId type, boolean isKeyframe, String fieldName) {
        FieldDef def = getField(type, isKeyframe, fieldName);
        return def != null ? def.defaultValue() : null;
    }

    /** 判定字段是否必填；字段未登记视为非必填。 */
    public static boolean isRequired(TrackTypeId type, boolean isKeyframe, String fieldName) {
        FieldDef def = getField(type, isKeyframe, fieldName);
        return def != null && def.required();
    }
}
