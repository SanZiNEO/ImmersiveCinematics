package com.immersivecinematics.migrate;

import com.immersivecinematics.immersive_cinematics.script.schema.TrackSchemas;
import com.immersivecinematics.immersive_cinematics.script.schema.TrackTypeSchema;
import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对象路径的唯一真源：启动时把 {@link TrackSchemas#all()} 展开成
 * {@code TrackTypeId -> (path -> ObjectDef)}。
 *
 * <p>迁移器不复制任何路径常量，只通过本类查询；schema 里没有的路径仍会写入，
 * 由调用方记「未知路径」。</p>
 */
public final class Schema {

    private final Map<TrackTypeId, Map<String, ObjectDef>> byType = new LinkedHashMap<>();
    private final Map<String, TrackTypeId> byId = new LinkedHashMap<>();

    public Schema() {
        for (Map.Entry<TrackTypeId, TrackTypeSchema> e : TrackSchemas.all().entrySet()) {
            TrackTypeId id = e.getKey();
            byType.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue().objects())));
            byId.put(id.id(), id);
        }
    }

    /** 旧脚本里的轨道类型名 → 内置 id（大写回退），未知类型原样构造。 */
    public TrackTypeId type(String id) {
        if (id == null || id.isBlank()) {
            return TrackTypeId.of("UNKNOWN");
        }
        TrackTypeId t = byId.get(id);
        if (t != null) {
            return t;
        }
        t = byId.get(id.toUpperCase(Locale.ROOT));
        return t != null ? t : TrackTypeId.of(id);
    }

    public Map<String, ObjectDef> objects(TrackTypeId type) {
        Map<String, ObjectDef> map = byType.get(type);
        return map != null ? map : Collections.emptyMap();
    }

    public ObjectDef def(TrackTypeId type, String path) {
        return path == null ? null : objects(type).get(path);
    }

    /** 路径规范化：已登记则返回 schema 的 path，否则原样。 */
    public String pathOf(TrackTypeId type, String raw) {
        ObjectDef d = def(type, raw);
        return d != null ? d.path() : raw;
    }

    public Object defaultOf(TrackTypeId type, String path) {
        ObjectDef d = def(type, path);
        return d != null ? d.defaultValue() : null;
    }

    public Interp interpOf(TrackTypeId type, String path) {
        ObjectDef d = def(type, path);
        return d == null || d.interp() == null ? Interp.NONE : d.interp();
    }

    public PropertyType typeOf(TrackTypeId type, String path) {
        ObjectDef d = def(type, path);
        return d != null ? d.type() : null;
    }

    public boolean exists(TrackTypeId type, String path) {
        return def(type, path) != null;
    }

    /** 默认值；schema 无默认时按类型给零值。NULLABLE_FLOAT 返回 null。 */
    public Object zeroOf(TrackTypeId type, String path) {
        Object def = defaultOf(type, path);
        if (def != null) {
            return def;
        }
        PropertyType t = typeOf(type, path);
        if (t == null) {
            return 0f;
        }
        return switch (t) {
            case FLOAT -> 0f;
            case INT -> 0;
            case BOOL -> false;
            case STRING, REF -> "";
            case ENUM -> {
                ObjectDef d = def(type, path);
                List<String> values = d != null ? d.enumValues() : List.of();
                yield values.isEmpty() ? "" : values.get(0);
            }
            case NULLABLE_FLOAT, STRUCT, EVENT_RECORD -> null;
        };
    }
}
