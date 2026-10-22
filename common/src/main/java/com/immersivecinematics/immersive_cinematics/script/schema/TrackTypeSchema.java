package com.immersivecinematics.immersive_cinematics.script.schema;

import java.util.Collections;
import java.util.Map;

/**
 * 单个轨道类型的 clip/keyframe 字段定义。
 *
 * @param clipFields 片段级字段表（不随关键帧变化）；null 归一为空表
 * @param kfFields 关键帧字段表（逐关键帧插值）；null 归一为空表
 */
public record TrackTypeSchema(Map<String, FieldDef> clipFields, Map<String, FieldDef> kfFields) {

    /** 空字段表：未登记的轨道类型回落到它。 */
    public static final TrackTypeSchema EMPTY =
            new TrackTypeSchema(Collections.emptyMap(), Collections.emptyMap());

    public TrackTypeSchema {
        clipFields = clipFields == null ? Collections.emptyMap() : clipFields;
        kfFields = kfFields == null ? Collections.emptyMap() : kfFields;
    }
}
