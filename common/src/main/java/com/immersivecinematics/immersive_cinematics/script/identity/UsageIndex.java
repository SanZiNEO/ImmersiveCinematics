package com.immersivecinematics.immersive_cinematics.script.identity;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 片段使用边的反向索引：从 {@link Clip} 对象身份反查全部使用边，并按脚本 id 解析片段。
 *
 * <p>构建期可变（{@link Builder}），{@link #freeze(Map)} 后只读：索引里的列表全部是不可变副本，
 * 运行期查询不分配、不加锁。片段键用 {@link IdentityHashMap}（同一 id 的重复片段会被校验器拒绝，
 * 索引只按对象身份区分实例），id 表用 {@link LinkedHashMap} 保脚本声明顺序。</p>
 */
public final class UsageIndex implements ClipResolver {

    private static final UsageIndex EMPTY = new UsageIndex(
            new IdentityHashMap<>(), Collections.emptyMap(), List.of());

    private final IdentityHashMap<Clip, List<UsageEdge>> byClip;
    private final Map<String, Clip> byId;
    private final List<UsageEdge> all;

    private UsageIndex(IdentityHashMap<Clip, List<UsageEdge>> byClip,
                       Map<String, Clip> byId, List<UsageEdge> all) {
        this.byClip = byClip;
        this.byId = byId;
        this.all = all;
    }

    /** 空索引（无任何使用边、无任何已知 id）。 */
    public static UsageIndex empty() {
        return EMPTY;
    }

    /** 新建可变构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 某个片段（按对象身份）的全部使用边；未登记过的片段返回空列表（不返回 {@code null}）。
     */
    public List<UsageEdge> edgesOf(Clip clip) {
        if (clip == null) return List.of();
        List<UsageEdge> edges = byClip.get(clip);
        return edges == null ? List.of() : edges;
    }

    /** 全部使用边（不可变视图，按登记顺序）。 */
    public List<UsageEdge> all() {
        return all;
    }

    /** 按脚本 id 解析片段；未知 / {@code null} → {@code null}。 */
    @Override
    public Clip resolve(String id) {
        return id == null ? null : byId.get(id);
    }

    /** 该 id 是否在本索引内可解析。 */
    public boolean hasId(String id) {
        return id != null && byId.containsKey(id);
    }

    /** 使用边构建器：解析期按脚本声明顺序登记，末尾 {@link #freeze(Map)} 一次。 */
    public static final class Builder {

        private final IdentityHashMap<Clip, List<UsageEdge>> byClip = new IdentityHashMap<>();
        private final List<UsageEdge> edges = new ArrayList<>();

        private Builder() {}

        /** 登记一条轨道内联放置（{@code TRACK_PLACEMENT}，入口局部时间 0）。 */
        public void placement(Clip clip, String hostPath) {
            reference(UsageKind.TRACK_PLACEMENT, hostPath, ClipRef.of(clip));
        }

        /** 登记一条任意种类的引用。 */
        public void reference(UsageKind kind, String hostPath, ClipRef ref) {
            UsageEdge edge = new UsageEdge(kind, hostPath, ref);
            edges.add(edge);
            byClip.computeIfAbsent(ref.clip(), k -> new ArrayList<>()).add(edge);
        }

        /** 冻结成只读索引。
         *
         * @param byId 权威 id → 片段表（{@code null} 视为空表）；表中的片段可被 {@link #resolve(String)} 命中
         */
        public UsageIndex freeze(Map<String, Clip> byId) {
            IdentityHashMap<Clip, List<UsageEdge>> frozenEdges = new IdentityHashMap<>(byClip.size());
            for (Map.Entry<Clip, List<UsageEdge>> entry : byClip.entrySet()) {
                frozenEdges.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            Map<String, Clip> ids = byId == null || byId.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(byId));
            return new UsageIndex(frozenEdges, ids, List.copyOf(edges));
        }
    }
}
