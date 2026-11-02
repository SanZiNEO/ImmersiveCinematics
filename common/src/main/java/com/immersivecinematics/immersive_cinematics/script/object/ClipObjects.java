package com.immersivecinematics.immersive_cinematics.script.object;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个片段的对象集合：点分路径 → 采样属性，保持作者书写顺序。
 *
 * <p>构造时做只读拷贝；实例不可变。缺省属性不创建，采样时回落 schema 默认值，因此
 * {@link #get(String)} 返回 {@code null} 是正常情况。</p>
 */
public final class ClipObjects {

    /** 空集合。 */
    public static final ClipObjects EMPTY = new ClipObjects(Collections.emptyMap());

    private final Map<String, SampledProperty> objects;

    /**
     * @param objects 路径 → 属性；{@code null} 不接受
     */
    public ClipObjects(Map<String, SampledProperty> objects) {
        Objects.requireNonNull(objects, "objects");
        Map<String, SampledProperty> copy = new LinkedHashMap<>(Math.max(16, objects.size() * 2));
        for (Map.Entry<String, SampledProperty> entry : objects.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey(), "objects 的路径不能为 null");
            SampledProperty property = Objects.requireNonNull(entry.getValue(), "objects[" + path + "] 不能为 null");
            copy.put(path, property);
        }
        this.objects = Collections.unmodifiableMap(copy);
    }

    /** 取属性；缺省返回 {@code null}。 */
    public SampledProperty get(String path) {
        return path == null ? null : objects.get(path);
    }

    /** 是否显式声明了该路径。 */
    public boolean contains(String path) {
        return path != null && objects.containsKey(path);
    }

    /** 全部路径，保持插入顺序；不可变。 */
    public Set<String> paths() {
        return objects.keySet();
    }

    /** 是否没有任何属性。 */
    public boolean isEmpty() {
        return objects.isEmpty();
    }

    /** 只读的路径 → 属性视图。 */
    public Map<String, SampledProperty> asMap() {
        return objects;
    }
}
