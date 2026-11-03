package com.immersivecinematics.immersive_cinematics.client.rule;

/**
 * 条件树的「事实」判定源：{@code clip_fact} / {@code rule_fact} 两类叶子的运行态查询入口。
 *
 * <p>事实按<b>最近一次执行</b>取值（见 {@code ROUND-I-4-MAP} §0.2-3）：片段事实指该片段当前执行或
 * 最近一次已完成执行；绑定的规则事实指该绑定最近一次激活。求值侧只读，不建历史、不跨实例。</p>
 *
 * <p>本接口有两个抽象方法，不是函数式接口——{@link #NONE} 提供恒 {@code false} 的空实现，
 * 供旧 3 参 {@link ClientConditions#evaluate} 使用（旧脚本无事实类型，行为逐位不变）。</p>
 */
public interface FactSource {

    /**
     * 片段事实。
     *
     * @param clipId  片段 id
     * @param started {@code true} = 该片段最近一次执行已开始（{@code started}）；{@code false} = 已完成（{@code completed}）
     * @return 事实是否成立
     */
    boolean clipFact(String clipId, boolean started);

    /**
     * 规则事实。
     *
     * @param bindingId      绑定 id
     * @param actionCompleted {@code true} = 该绑定的动作已执行完成（{@code action_completed}）；
     *                        {@code false} = 规则判定已成立（{@code succeeded}）
     * @return 事实是否成立
     */
    boolean ruleFact(String bindingId, boolean actionCompleted);

    /** 空事实源：一切事实恒不成立（旧 3 参求值路径的唯一实现）。 */
    FactSource NONE = new FactSource() {
        @Override
        public boolean clipFact(String clipId, boolean started) {
            return false;
        }

        @Override
        public boolean ruleFact(String bindingId, boolean actionCompleted) {
            return false;
        }
    };
}
