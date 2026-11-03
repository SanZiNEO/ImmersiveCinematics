package com.immersivecinematics.immersive_cinematics.script.path;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本级路径集合：{@code paths[]} 的只读索引（{@code ROUND-I-4-MAP} §1.6 / §2.6）。
 *
 * <p>两张索引：id → 条目（保声明顺序）与片段身份 → 条目列表（{@link IdentityHashMap}，同一 id 的重复
 * 片段由校验器拒绝，这里只按对象身份区分实例）。{@link #entriesFor(Clip)} 无命中返回空列表，
 * 不返回 {@code null}，稳态查询不分配集合。</p>
 */
public final class PathCollection {

    /** 空集合（无 {@code paths} 的脚本共用同一实例）。 */
    public static final PathCollection EMPTY = new PathCollection(List.of());

    private final List<PathEntry> entries;
    private final Map<String, PathEntry> byId;
    private final IdentityHashMap<Clip, List<PathEntry>> byClip;

    private PathCollection(List<PathEntry> entries) {
        this.entries = entries;
        if (entries.isEmpty()) {
            this.byId = Collections.emptyMap();
            this.byClip = new IdentityHashMap<>(0);
            return;
        }
        Map<String, PathEntry> ids = new LinkedHashMap<>(entries.size());
        IdentityHashMap<Clip, List<PathEntry>> clips = new IdentityHashMap<>();
        for (PathEntry entry : entries) {
            PathEntry previous = ids.putIfAbsent(entry.id(), entry);
            if (previous != null) {
                throw new IllegalArgumentException("路径 id 重复: " + entry.id());
            }
            clips.computeIfAbsent(entry.target(), k -> new ArrayList<>(2)).add(entry);
        }
        for (Map.Entry<Clip, List<PathEntry>> bucket : clips.entrySet()) {
            bucket.setValue(List.copyOf(bucket.getValue()));
        }
        this.byId = Collections.unmodifiableMap(ids);
        this.byClip = clips;
    }

    /**
     * 由条目列表构建只读集合（保声明顺序）。
     *
     * @param entries 条目（{@code null} / 空 → {@link #EMPTY}）
     * @return 不可变集合
     * @throws IllegalArgumentException 条目为 {@code null} 或 id 重复
     */
    public static PathCollection of(List<PathEntry> entries) {
        if (entries == null || entries.isEmpty()) return EMPTY;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == null) {
                throw new IllegalArgumentException("路径集合：entries[" + i + "] 不能为 null");
            }
        }
        return new PathCollection(List.copyOf(entries));
    }

    /** 全部条目（不可变，按脚本声明顺序）。 */
    public List<PathEntry> entries() {
        return entries;
    }

    /** 按 id 取条目；未知 / {@code null} → {@code null}。 */
    public PathEntry entry(String id) {
        return id == null ? null : byId.get(id);
    }

    /** 作用于该片段（按对象身份）的条目；无 → 空列表。 */
    public List<PathEntry> entriesFor(Clip clip) {
        if (clip == null) return List.of();
        List<PathEntry> bucket = byClip.get(clip);
        return bucket == null ? List.of() : bucket;
    }

    /** 集合是否为空。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
