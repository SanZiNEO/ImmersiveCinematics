package com.immersivecinematics.immersive_cinematics.script.schema;

import java.util.Collections;
import java.util.Map;

/**
 * 单个轨道类型的 clip/keyframe 字段定义（0.3.5 第5轮 5B）。
 */
public record TrackTypeSchema(Map<String, FieldDef> clipFields, Map<String, FieldDef> kfFields) {

    public static final TrackTypeSchema EMPTY =
            new TrackTypeSchema(Collections.emptyMap(), Collections.emptyMap());

    public TrackTypeSchema {
        clipFields = clipFields == null ? Collections.emptyMap() : clipFields;
        kfFields = kfFields == null ? Collections.emptyMap() : kfFields;
    }
}
