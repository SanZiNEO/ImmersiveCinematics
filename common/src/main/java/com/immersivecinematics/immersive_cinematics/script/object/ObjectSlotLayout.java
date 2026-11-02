package com.immersivecinematics.immersive_cinematics.script.object;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.schema.ObjectDef;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个轨道类型的对象槽位布局：{@code SchemaRegistry.getObjects(type)} 的迭代顺序 → 固定 slot。
 *
 * <p>槽位在加载期编译一次，同一轨道类型的所有片段共用；运行时按 {@code slot} 读
 * {@link SampledClip} 的 typed arrays，不做字符串查表。实例不可变（{@code defs()}/{@code slotOf}
 * 都只读），按 {@link TrackType} 缓存。</p>
 *
 * <p>槽位顺序 = schema 声明的迭代顺序（{@code LinkedHashMap} 的插入顺序），因此新增/删除对象只会
 * 让后续槽位移动，不会改变既有对象的相对顺序语义。</p>
 */
public final class ObjectSlotLayout {

    /** 轨道类型 → 布局；首次访问建成，之后复用。 */
    private static final ConcurrentHashMap<TrackType, ObjectSlotLayout> CACHE = new ConcurrentHashMap<>();

    private final ObjectDef[] defs;
    private final PropertyType[] types;
    private final Map<String, Integer> slots;
    private final List<ObjectDef> defList;

    private ObjectSlotLayout(TrackType trackType) {
        Map<String, ObjectDef> source = SchemaRegistry.getObjects(trackType);
        int count = source.size();
        ObjectDef[] defs = new ObjectDef[count];
        PropertyType[] types = new PropertyType[count];
        Map<String, Integer> slots = new HashMap<>(Math.max(16, count * 2));
        int index = 0;
        for (Map.Entry<String, ObjectDef> entry : source.entrySet()) {
            ObjectDef def = entry.getValue();
            defs[index] = def;
            types[index] = def.type();
            slots.put(entry.getKey(), index);
            index++;
        }
        this.defs = defs;
        this.types = types;
        this.slots = slots;
        this.defList = List.of(defs);
    }

    /**
     * 取轨道类型的槽位布局；同一类型返回同一实例。
     *
     * @param type 轨道类型；{@code null} 不接受
     * @return 不可变布局
     */
    public static ObjectSlotLayout of(TrackType type) {
        Objects.requireNonNull(type, "type");
        return CACHE.computeIfAbsent(type, ObjectSlotLayout::new);
    }

    /**
     * 路径 → 槽位。
     *
     * @param path 点分路径
     * @return 槽位下标；未登记（含 {@code null}）返回 {@code -1}，不抛异常
     */
    public int slotOf(String path) {
        if (path == null) {
            return -1;
        }
        Integer slot = slots.get(path);
        return slot != null ? slot : -1;
    }

    /**
     * 槽位的值类型。
     *
     * @param slot 槽位下标
     * @return 该槽位声明的 {@link PropertyType}
     * @throws IndexOutOfBoundsException 槽位越界（编程错误）
     */
    public PropertyType typeOf(int slot) {
        return types[slot];
    }

    /** 按槽位顺序排列的声明；不可变。 */
    public List<ObjectDef> defs() {
        return defList;
    }

    /** 槽位数量。 */
    public int size() {
        return defs.length;
    }
}
