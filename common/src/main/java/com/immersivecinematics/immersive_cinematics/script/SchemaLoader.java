package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaRegistry;
import com.immersivecinematics.immersive_cinematics.script.schema.TrackTypeSchema;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 字段定义查询入口：schema 驱动的字段元数据（类型 / 缺省 / 必填 / 枚举域）全部来自 {@link SchemaRegistry} 的 Java 元数据，
 * 本类只做转发——调用方据此解析与校验脚本，无需知道 schema 的存储形态。
 */
public final class SchemaLoader {

    private SchemaLoader() {}

    /**
     * 取某轨道类型的完整 schema。
     *
     * @param type 轨道类型
     * @return 该类型的字段定义（clip 级 + 关键帧级）
     */
    public static TrackTypeSchema get(TrackType type) {
        return SchemaRegistry.get(type);
    }

    /**
     * 取字段缺省值。
     *
     * @param type       轨道类型
     * @param isKeyframe true = 关键帧字段，false = clip 字段
     * @param fieldName  字段名
     * @return 缺省值；字段不存在返回 {@code null}
     */
    public static Object getDefaultValue(TrackType type, boolean isKeyframe, String fieldName) {
        return SchemaRegistry.getDefaultValue(type, isKeyframe, fieldName);
    }

    /**
     * 字段是否必填。
     *
     * @param type       轨道类型
     * @param isKeyframe true = 关键帧字段，false = clip 字段
     * @param fieldName  字段名
     * @return 必填返回 true；字段不存在返回 false
     */
    public static boolean isRequired(TrackType type, boolean isKeyframe, String fieldName) {
        return SchemaRegistry.isRequired(type, isKeyframe, fieldName);
    }

    /**
     * 字段是否存在于该类型的 schema 中。
     *
     * @param type       轨道类型
     * @param isKeyframe true = 关键帧字段，false = clip 字段
     * @param fieldName  字段名
     * @return 存在返回 true
     */
    public static boolean hasField(TrackType type, boolean isKeyframe, String fieldName) {
        return SchemaRegistry.hasField(type, isKeyframe, fieldName);
    }

    /**
     * 取 clip 级字段表。
     *
     * @param type 轨道类型
     * @return 字段名 → 定义（只读视图）
     */
    public static Map<String, FieldDef> getClipFields(TrackType type) {
        return SchemaRegistry.getClipFields(type);
    }

    /**
     * 取关键帧级字段表。
     *
     * @param type 轨道类型
     * @return 字段名 → 定义（只读视图）
     */
    public static Map<String, FieldDef> getKeyframeFields(TrackType type) {
        return SchemaRegistry.getKeyframeFields(type);
    }

    /**
     * 取字段的枚举取值域。
     *
     * @param type       轨道类型
     * @param isKeyframe true = 关键帧字段，false = clip 字段
     * @param fieldName  字段名
     * @return 取值列表；字段不存在或非枚举返回空列表
     */
    public static List<String> getEnumValues(TrackType type, boolean isKeyframe, String fieldName) {
        FieldDef def = isKeyframe ? getKeyframeFields(type).get(fieldName) : getClipFields(type).get(fieldName);
        return def != null ? def.enumValues() : Collections.emptyList();
    }

    /**
     * 取 meta 级字段表。
     *
     * @return 字段名 → 定义（只读视图）
     */
    public static Map<String, FieldDef> getMetaFields() {
        return SchemaRegistry.getMetaFields();
    }
}
