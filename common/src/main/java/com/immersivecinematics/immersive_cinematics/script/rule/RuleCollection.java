package com.immersivecinematics.immersive_cinematics.script.rule;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本级规则集合：规则定义表 + 绑定表 + 两条索引。
 *
 * <p>不可变：构造后所有集合字段只读。索引口径：
 * <ul>
 *   <li>{@link #definition(String)} / {@link #binding(String)} 用 {@link LinkedHashMap}（保脚本声明顺序，
 *       便于诊断与编辑器回显）；未知 id → {@code null}；</li>
 *   <li>{@link #bindingsFor(Clip)} 用 {@link IdentityHashMap}（同一片段对象的多条绑定），
 *       未知片段 → 空列表（不返回 {@code null}）。</li>
 * </ul>
 * 运行期按 id 查表求值（{@code clip_fact} / {@code rule_fact} 与 {@code fact} 调度），零分配。</p>
 */
public final class RuleCollection {

    /** 空集合（无定义、无绑定）。 */
    public static final RuleCollection EMPTY = new RuleCollection(
            List.of(), Collections.emptyMap(), List.of(), Collections.emptyMap(), new IdentityHashMap<>());

    private final List<RuleDefinition> definitions;
    private final Map<String, RuleDefinition> definitionsById;
    private final List<RuleBinding> bindings;
    private final Map<String, RuleBinding> bindingsById;
    private final IdentityHashMap<Clip, List<RuleBinding>> bindingsByClip;

    private RuleCollection(List<RuleDefinition> definitions,
                           Map<String, RuleDefinition> definitionsById,
                           List<RuleBinding> bindings,
                           Map<String, RuleBinding> bindingsById,
                           IdentityHashMap<Clip, List<RuleBinding>> bindingsByClip) {
        this.definitions = definitions;
        this.definitionsById = definitionsById;
        this.bindings = bindings;
        this.bindingsById = bindingsById;
        this.bindingsByClip = bindingsByClip;
    }

    /**
     * 由已校验的定义表与绑定表构建集合（两表各自按声明顺序去重；重复 id 由解析器 / 校验器拦下）。
     *
     * @param definitions 规则定义（不得为 {@code null}）
     * @param bindings    规则绑定（不得为 {@code null}）
     * @return 不可变集合
     */
    public static RuleCollection of(List<RuleDefinition> definitions, List<RuleBinding> bindings) {
        if ((definitions == null || definitions.isEmpty()) && (bindings == null || bindings.isEmpty())) {
            return EMPTY;
        }
        List<RuleDefinition> defs = definitions == null ? List.of() : List.copyOf(definitions);
        List<RuleBinding> binds = bindings == null ? List.of() : List.copyOf(bindings);

        Map<String, RuleDefinition> defMap = new LinkedHashMap<>(defs.size());
        for (RuleDefinition definition : defs) {
            defMap.putIfAbsent(definition.id(), definition);
        }

        Map<String, RuleBinding> bindMap = new LinkedHashMap<>(binds.size());
        IdentityHashMap<Clip, List<RuleBinding>> byClip = new IdentityHashMap<>();
        for (RuleBinding binding : binds) {
            bindMap.putIfAbsent(binding.id(), binding);
            byClip.computeIfAbsent(binding.target(), key -> new ArrayList<>(2)).add(binding);
        }
        for (Map.Entry<Clip, List<RuleBinding>> entry : byClip.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }

        return new RuleCollection(defs, Collections.unmodifiableMap(defMap), binds,
                Collections.unmodifiableMap(bindMap), byClip);
    }

    /** 规则定义表（不可变，按脚本声明顺序）。 */
    public List<RuleDefinition> definitions() {
        return definitions;
    }

    /** 按 id 查规则定义；未知返回 {@code null}。 */
    public RuleDefinition definition(String id) {
        return id == null ? null : definitionsById.get(id);
    }

    /** 绑定表（不可变，按脚本声明顺序）。 */
    public List<RuleBinding> bindings() {
        return bindings;
    }

    /** 按 id 查绑定；未知返回 {@code null}。 */
    public RuleBinding binding(String id) {
        return id == null ? null : bindingsById.get(id);
    }

    /** 某片段（按对象身份）的全部绑定；无 → 空列表。 */
    public List<RuleBinding> bindingsFor(Clip clip) {
        if (clip == null) return List.of();
        List<RuleBinding> list = bindingsByClip.get(clip);
        return list == null ? List.of() : list;
    }

    /** 是否为空集合（既无定义也无绑定）。 */
    public boolean isEmpty() {
        return definitions.isEmpty() && bindings.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("RuleCollection{definitions=%d, bindings=%d}",
                definitions.size(), bindings.size());
    }
}
