package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.track.TrackRegistry;
import com.immersivecinematics.immersive_cinematics.spi.FieldDef;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.Map;

/**
 * Java 字段元数据统一入口。
 *
 * <p>字段元数据由 {@link MetaSchemas} 与各轨道的 {@link TrackTypeSchema} 提供；轨道表本体在
 * {@link TrackRegistry}（内置 7 轨 + 第三方注册），本类只转发。</p>
 */
public final class SchemaRegistry {

    private static final Map<String, FieldDef> META = MetaSchemas.all();

    private SchemaRegistry() {}

    /** 取轨道类型的 schema；未登记的类型返回 {@link TrackTypeSchema#EMPTY}。 */
    public static TrackTypeSchema get(TrackTypeId type) {
        return TrackRegistry.schema(type);
    }

    /** 取轨道类型的对象声明表（路径 → 声明）。 */
    public static Map<String, ObjectDef> getObjects(TrackTypeId type) {
        return get(type).objects();
    }

    /** 取对象声明；未登记返回 null。 */
    public static ObjectDef getObject(TrackTypeId type, String path) {
        return path == null ? null : getObjects(type).get(path);
    }

    /** 取脚本 meta 字段表（只读共享，调用方不得修改）。 */
    public static Map<String, FieldDef> getMetaFields() {
        return META;
    }
}
