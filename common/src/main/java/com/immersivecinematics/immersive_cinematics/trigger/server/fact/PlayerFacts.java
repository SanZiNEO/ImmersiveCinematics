package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 单个玩家的事实存储：按类型分片，每片 = 保留集（窗口查询）+ 常驻索引（累计查询）。
 *
 * <p><b>查询口径</b>：
 * <ul>
 *   <li>{@link #exists} / {@link #count} / {@link #last(FactType, String, String)} / {@link #lastOf}
 *       走常驻索引，是<b>累计口径</b>：保留集按 {@link FactType#maxRetained()} 淘汰旧事实后，计数与「最近一次」
 *       仍然成立。键 = {@code script + '\u0000' + object}。</li>
 *   <li>{@link #anyWithin} / {@link #lastWithin} /
 *       {@link #last(FactType, String, String, FactResult)}
 *       扫描保留集，<b>受保留深度约束</b>：比保留集最旧事实更早的记录查不到。保留上限按类型声明，
 *       窗口口径（{@code dwell} / {@code window} ≤ 1200 tick）应按对应类型的上限取用。</li>
 * </ul>
 *
 * <p>保留集按 append 顺序保存；调用方应保证 tick 非降序，窗口查询依赖这一点做提前停止
 * （从新到旧扫描，遇到 {@code tick < fromTick} 立即返回）。
 *
 * <p>脏位只由 {@link FactType.Durability#PERSISTENT} 事实的追加置起：
 * 会话事实不参与落盘，追加它们不应触发写文件。
 */
public final class PlayerFacts {

    private static final char KEY_SEPARATOR = '\u0000';

    private final EnumMap<FactType, TypeFacts> types = new EnumMap<>(FactType.class);
    private boolean dirty;

    // ===== Append =====

    /** 追加一条事实；持久事实会置脏位，保留集超出上限时丢最旧。 */
    public void append(Fact fact) {
        Objects.requireNonNull(fact, "fact");
        TypeFacts typeFacts = types.computeIfAbsent(fact.type(), TypeFacts::new);
        typeFacts.append(fact);
        if (fact.type().durability() == FactType.Durability.PERSISTENT) {
            dirty = true;
        }
    }

    // ===== Cumulative queries (index-backed) =====

    /** 该 (类型, 脚本, 对象) 是否出现过；累计口径，不受保留集淘汰影响。 */
    public boolean exists(FactType type, String script, String object) {
        return indexEntry(type, script, object) != null;
    }

    /** 该 (类型, 脚本, 对象) 的累计条数；累计口径，不受保留集淘汰影响。 */
    public int count(FactType type, String script, String object) {
        IndexEntry entry = indexEntry(type, script, object);
        return entry == null ? 0 : entry.count;
    }

    /** 该 (类型, 脚本, 对象) 索引里的最后一条；累计口径（即使该条已被保留集淘汰也返回）。 */
    public Fact last(FactType type, String script, String object) {
        IndexEntry entry = indexEntry(type, script, object);
        return entry == null ? null : entry.last;
    }

    /**
     * 该类型在该脚本下最后一条事实，<b>不分 {@code object}</b>：扫描每个索引键的最后一条，取 tick 最大者。
     * 累计口径（不受保留集淘汰影响）。
     *
     * <p>单值求值器「读最近一次事件」必须用这个方法，不能用「最后一条匹配条件的事实」——
     * 两条语义在事件流里不等价（最近一次事件不满足条件时求值应为假）。
     */
    public Fact lastOf(FactType type, String script) {
        TypeFacts typeFacts = types.get(type);
        if (typeFacts == null) {
            return null;
        }
        String expectedScript = normalize(script);
        Fact latest = null;
        for (Map.Entry<String, IndexEntry> entry : typeFacts.index.entrySet()) {
            if (!keyHasScript(entry.getKey(), expectedScript)) {
                continue;
            }
            Fact last = entry.getValue().last;
            if (last != null && (latest == null || last.tick() > latest.tick())) {
                latest = last;
            }
        }
        return latest;
    }

    // ===== Retention-window queries =====

    /**
     * 该 (类型, 脚本, 对象) 最近一条结果等于 {@code requiredResult} 的事实。
     *
     * <p>走保留集（索引只保留最后一条，无法回答「最近一次 FINISHED」这类问题），
     * 因此受保留深度约束；找不到返回 {@code null}。
     */
    public Fact last(FactType type, String script, String object, FactResult requiredResult) {
        TypeFacts typeFacts = types.get(type);
        if (typeFacts == null) {
            return null;
        }
        String expectedScript = normalize(script);
        String expectedObject = normalize(object);
        FactResult expectedResult = requiredResult == null ? FactResult.NONE : requiredResult;
        Iterator<Fact> iterator = typeFacts.retained.descendingIterator();
        while (iterator.hasNext()) {
            Fact fact = iterator.next();
            if (fact.script().equals(expectedScript)
                    && fact.object().equals(expectedObject)
                    && fact.result() == expectedResult) {
                return fact;
            }
        }
        return null;
    }

    /**
     * 闭区间 {@code [fromTick, toTick]} 内是否存在匹配事实；只读保留集，从新到旧扫描，
     * 遇到 {@code tick < fromTick} 立即停止（append 顺序 = tick 非降序）。
     */
    public boolean anyWithin(FactType type, String script, String object, int fromTick, int toTick) {
        if (fromTick > toTick) {
            return false;
        }
        TypeFacts typeFacts = types.get(type);
        if (typeFacts == null) {
            return false;
        }
        String expectedScript = normalize(script);
        String expectedObject = normalize(object);
        Iterator<Fact> iterator = typeFacts.retained.descendingIterator();
        while (iterator.hasNext()) {
            Fact fact = iterator.next();
            if (fact.tick() < fromTick) {
                return false;
            }
            if (fact.tick() <= toTick
                    && fact.script().equals(expectedScript)
                    && fact.object().equals(expectedObject)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 闭区间 {@code [fromTick, toTick]} 内最近一条满足 {@code filter} 的该脚本事实；
     * {@code filter == null} 视为不过滤。只读保留集，遇到 {@code tick < fromTick} 立即停止。
     */
    public Fact lastWithin(FactType type, String script, int fromTick, int toTick, Predicate<Fact> filter) {
        if (fromTick > toTick) {
            return null;
        }
        TypeFacts typeFacts = types.get(type);
        if (typeFacts == null) {
            return null;
        }
        String expectedScript = normalize(script);
        Predicate<Fact> predicate = filter == null ? fact -> true : filter;
        Iterator<Fact> iterator = typeFacts.retained.descendingIterator();
        while (iterator.hasNext()) {
            Fact fact = iterator.next();
            if (fact.tick() < fromTick) {
                return null;
            }
            if (fact.tick() <= toTick && fact.script().equals(expectedScript) && predicate.test(fact)) {
                return fact;
            }
        }
        return null;
    }

    /** 该类型保留集的只读快照（append 顺序，最旧在前）。 */
    public List<Fact> retained(FactType type) {
        TypeFacts typeFacts = types.get(type);
        return typeFacts == null ? List.of() : List.copyOf(typeFacts.retained);
    }

    // ===== Instance cleanup =====

    /**
     * 实例结束时清理该实例的跳过票：从保留集与索引同时移除。
     *
     * <p>只处理 {@link FactType#SKIP_VOTE}（其保留上限为 0，不按条数淘汰，因此索引计数
     * 可以由保留集重算）。会话事实，不置脏位。
     */
    public void removeInstance(String script, String instance) {
        TypeFacts typeFacts = types.get(FactType.SKIP_VOTE);
        if (typeFacts == null) {
            return;
        }
        String expectedScript = normalize(script);
        String expectedInstance = normalize(instance);
        Map<String, Integer> removedByKey = new HashMap<>();
        Iterator<Fact> iterator = typeFacts.retained.iterator();
        while (iterator.hasNext()) {
            Fact fact = iterator.next();
            if (fact.script().equals(expectedScript) && fact.instance().equals(expectedInstance)) {
                iterator.remove();
                removedByKey.merge(key(fact.script(), fact.object()), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> removed : removedByKey.entrySet()) {
            String key = removed.getKey();
            IndexEntry entry = typeFacts.index.get(key);
            if (entry == null) {
                continue;
            }
            entry.count -= removed.getValue();
            if (entry.count <= 0) {
                typeFacts.index.remove(key);
            } else {
                Fact last = lastFor(typeFacts, key);
                if (last == null) {
                    typeFacts.index.remove(key);
                } else {
                    entry.last = last;
                }
            }
        }
    }

    // ===== Persistence state =====

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /** 只收集持久类型的事实（保留集内的），供 SNBT v2 落盘。 */
    public List<Fact> persistentFacts() {
        List<Fact> facts = new ArrayList<>();
        for (Map.Entry<FactType, TypeFacts> entry : types.entrySet()) {
            if (entry.getKey().durability() == FactType.Durability.PERSISTENT) {
                facts.addAll(entry.getValue().retained);
            }
        }
        return List.copyOf(facts);
    }

    // ===== Internals =====

    private IndexEntry indexEntry(FactType type, String script, String object) {
        TypeFacts typeFacts = types.get(type);
        return typeFacts == null ? null : typeFacts.index.get(key(normalize(script), normalize(object)));
    }

    private static Fact lastFor(TypeFacts typeFacts, String key) {
        int separator = key.indexOf(KEY_SEPARATOR);
        String script = separator < 0 ? key : key.substring(0, separator);
        String object = separator < 0 ? "" : key.substring(separator + 1);
        Iterator<Fact> iterator = typeFacts.retained.descendingIterator();
        while (iterator.hasNext()) {
            Fact fact = iterator.next();
            if (fact.script().equals(script) && fact.object().equals(object)) {
                return fact;
            }
        }
        return null;
    }

    private static String key(String script, String object) {
        return script + KEY_SEPARATOR + object;
    }

    /** 索引键是否属于该脚本（不切分字符串：避免每次查询都造子串）。 */
    private static boolean keyHasScript(String key, String script) {
        return key.length() > script.length()
                && key.startsWith(script)
                && key.charAt(script.length()) == KEY_SEPARATOR;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    /** 每类型的保留集 + 常驻索引；索引不随保留集淘汰。 */
    private static final class TypeFacts {

        private final FactType type;
        private final ArrayDeque<Fact> retained = new ArrayDeque<>();
        private final Map<String, IndexEntry> index = new HashMap<>();

        private TypeFacts(FactType type) {
            this.type = type;
        }

        private void append(Fact fact) {
            retained.addLast(fact);
            int limit = type.maxRetained();
            if (limit > 0) {
                while (retained.size() > limit) {
                    retained.removeFirst();
                }
            }
            IndexEntry entry = index.computeIfAbsent(key(fact.script(), fact.object()), ignored -> new IndexEntry());
            entry.count++;
            entry.last = fact;
        }
    }

    /** 索引条目：累计计数 + 最后一条；不随保留集淘汰。 */
    private static final class IndexEntry {
        private Fact last;
        private int count;
    }
}
