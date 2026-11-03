package com.immersivecinematics.immersive_cinematics.script.path;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本级路径集合的运行时来源：把 {@link PathEntry} 映射成 {@link PathBinding}，按片段身份缓存。
 *
 * <p>首次访问某个片段时建立该片段的绑定列表（不可变副本），此后只读命中缓存——稳态求值不分配集合。
 * 脚本替换 = 新建来源（{@link #clear()} 只在同一来源被复用 / 重置时使用）；绑定列表顺序 = 脚本声明顺序，
 * 同区间重叠时由 {@link PathBindingResolver} 的「起点更晚者优先」裁决。</p>
 */
public final class PathCollectionSource implements PathBindingSource {

    private final PathCollection collection;
    private final Map<Clip, List<PathBinding>> bindings = new IdentityHashMap<>();

    /**
     * @param collection 路径集合（{@code null} → {@link PathCollection#EMPTY}）
     */
    public PathCollectionSource(PathCollection collection) {
        this.collection = collection == null ? PathCollection.EMPTY : collection;
    }

    @Override
    public List<PathBinding> bindingsFor(Clip clip) {
        if (clip == null) return List.of();
        List<PathBinding> cached = bindings.get(clip);
        if (cached != null) return cached;
        List<PathEntry> entries = collection.entriesFor(clip);
        if (entries.isEmpty()) {
            bindings.put(clip, List.of());
            return List.of();
        }
        List<PathBinding> mapped = new ArrayList<>(entries.size());
        for (PathEntry entry : entries) {
            mapped.add(new PathBinding(entry.target(), entry.from(), entry.to(), entry.geometry(),
                    entry.axes(), entry.timing()));
        }
        List<PathBinding> frozen = List.copyOf(mapped);
        bindings.put(clip, frozen);
        return frozen;
    }

    /** 丢弃全部片段缓存（脚本替换 / 停播 / seek 后的重新绑定）。 */
    public void clear() {
        bindings.clear();
    }
}
