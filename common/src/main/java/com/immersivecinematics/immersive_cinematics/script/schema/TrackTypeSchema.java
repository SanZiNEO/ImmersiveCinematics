package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.spi.FieldDef;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;

import java.util.Collections;
import java.util.Map;

/**
 * 单个轨道类型的 schema：对象声明表 + 片段播放控制字段表。
 *
 * <p>两张表只在 schema 导出与注册期自检时使用；运行时按对象路径采样，不读这里。</p>
 *
 * @param objects 对象模型的路径 → 声明；null 归一为空表
 * @param playControl 片段播放控制字段表（七轨共用）；null 归一为空表
 */
public record TrackTypeSchema(Map<String, ObjectDef> objects, Map<String, FieldDef> playControl) {

    /** 空 schema：未登记的轨道类型回落到它。 */
    public static final TrackTypeSchema EMPTY =
            new TrackTypeSchema(Collections.emptyMap(), Collections.emptyMap());

    public TrackTypeSchema {
        objects = objects == null ? Collections.emptyMap() : objects;
        playControl = playControl == null ? Collections.emptyMap() : playControl;
    }
}
