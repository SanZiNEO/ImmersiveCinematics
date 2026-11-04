package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;

import java.util.Collections;
import java.util.Map;

/**
 * 单个轨道类型的字段定义。
 *
 * @param clipFields 片段级字段表（旧链路，不随关键帧变化）；null 归一为空表
 * @param kfFields 关键帧字段表（旧链路，逐关键帧插值）；null 归一为空表
 * @param objects 新对象模型的路径 → 声明；null 归一为空表
 * @param playControl 片段播放控制字段表（七轨共用）；null 归一为空表
 */
public record TrackTypeSchema(Map<String, FieldDef> clipFields, Map<String, FieldDef> kfFields,
                              Map<String, ObjectDef> objects, Map<String, FieldDef> playControl) {

    /** 空字段表：未登记的轨道类型回落到它。 */
    public static final TrackTypeSchema EMPTY =
            new TrackTypeSchema(Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap());

    public TrackTypeSchema {
        clipFields = clipFields == null ? Collections.emptyMap() : clipFields;
        kfFields = kfFields == null ? Collections.emptyMap() : kfFields;
        objects = objects == null ? Collections.emptyMap() : objects;
        playControl = playControl == null ? Collections.emptyMap() : playControl;
    }

    /** 旧链路构造：无对象声明、无播放控制表。 */
    public TrackTypeSchema(Map<String, FieldDef> clipFields, Map<String, FieldDef> kfFields) {
        this(clipFields, kfFields, Collections.emptyMap(), Collections.emptyMap());
    }
}
