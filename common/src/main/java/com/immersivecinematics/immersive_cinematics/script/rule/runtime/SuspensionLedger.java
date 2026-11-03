package com.immersivecinematics.immersive_cinematics.script.rule.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 实例级挂起来的来源账本：按来源字符串分别记账，同来源幂等（{@link Set} 语义）。
 *
 * <p>本类只记账，不认识 {@code ScriptPlayer}：内容冻结 / 恢复由 {@link RuleRuntime} 在
 * 「0 → 1」（首个来源获取）与「1 → 0」（最后一个来源释放）的边沿调
 * {@code ScriptPlayer#beginSuspend()} / {@code ScriptPlayer#endSuspend()}。于是多个来源
 * （{@code control=hold} 判定持有 {@code rule:<bindingId>}、{@code pause} 动作
 * {@link #ACTION_PAUSE}）不会互相覆盖：只有最后一个来源释放时内容位置才恢复推进。</p>
 *
 * <p>分配契约：{@link #acquire} / {@link #release} / {@link #held} / {@link #holdCount} /
 * {@link #has} 帧内可用——来源字符串由调用方预分配（如 {@code "rule:" + binding.id()}），
 * 这里只做哈希查找。{@link #sources()} 会新建快照集合，仅供诊断 / 探针使用。</p>
 *
 * <p>线程契约：仅客户端主线程。</p>
 */
public final class SuspensionLedger {

    /** {@code pause} 动作造成的持有来源（{@code resume} 释放全部）。 */
    public static final String ACTION_PAUSE = "action:pause";

    /** 规则判定持有的来源前缀（完整键 = 前缀 + 绑定 id）。 */
    public static final String RULE_HOLD_PREFIX = "rule:";

    /** 当前持有的来源（字符串由调用方预分配，稳定不变）。 */
    private final Set<String> sources = new HashSet<>(4);

    /**
     * 获取一个来源（幂等）。
     *
     * @param source 来源标识（{@code null} = 无操作）
     */
    public void acquire(String source) {
        if (source != null) {
            sources.add(source);
        }
    }

    /**
     * 释放一个来源；不存在时为无操作（幂等）。
     *
     * @param source 来源标识（{@code null} = 无操作）
     */
    public void release(String source) {
        if (source != null) {
            sources.remove(source);
        }
    }

    /** 是否仍有来源持有（= 内容位置应保持）。 */
    public boolean held() {
        return !sources.isEmpty();
    }

    /** 当前持有来源数。 */
    public int holdCount() {
        return sources.size();
    }

    /** 指定来源是否持有。 */
    public boolean has(String source) {
        return source != null && sources.contains(source);
    }

    /** 清空全部来源（引擎重建 / 停播回收）。 */
    public void clear() {
        sources.clear();
    }

    /**
     * 释放所有以 {@code prefix} 开头的来源（{@code pause} → {@code resume} 用）。
     *
     * <p>只按前缀匹配：{@code rule:} 前缀不会命中 {@code action:pause}。分配契约：仅动作 /
     * 诊断路径调用（迭代器 + 删除），不进帧内稳态。</p>
     *
     * @param prefix 来源前缀（{@code null} / 空 = 无操作）
     * @return 实际释放的来源数
     */
    public int releaseAll(String prefix) {
        if (prefix == null || prefix.isEmpty() || sources.isEmpty()) return 0;
        int released = 0;
        var iterator = sources.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) {
                iterator.remove();
                released++;
            }
        }
        return released;
    }

    /**
     * 当前来源的不可变快照（顺序按插入顺序，便于诊断回显）。
     *
     * <p>分配契约：每次调用新建列表，<b>仅</b>用于诊断 / 探针，不得进帧内稳态路径。</p>
     */
    public List<String> sources() {
        return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(sources)));
    }

    @Override
    public String toString() {
        return "SuspensionLedger" + sources();
    }
}
