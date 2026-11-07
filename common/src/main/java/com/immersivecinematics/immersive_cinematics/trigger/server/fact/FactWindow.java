package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import com.google.gson.JsonObject;

/**
 * 窗口判定：事件类触发器在闭区间 {@code [fromTick, toTick]} 内是否满足过条件。
 *
 * <p>与单点求值器的差别只在「取哪条事实」：单点取该类型<b>最后一条</b>事实（{@link PlayerFacts#lastOf}），
 * 窗口取<b>窗口内任意一条</b>（{@link PlayerFacts#lastWithin}）。事实级匹配口径完全复用
 * {@link EventFactMatch#matches(Fact, JsonObject)}，不另立一套字段解释。
 *
 * <p>多目标判定统一由组合器 {@code any} / {@code all_of} 表达：本类只做单值条件的窗口判定
 * （{@code entity_kill} / {@code item_pickup} 的 {@code entity} / {@code item} 只认字符串）。
 *
 * <p><b>{@code dimension_change}</b>：本类只判 {@code from_dimension}（来源维度）与「窗口内是否有维度切换」；
 * 目标维度是玩家<b>当前</b>状态，由调用方先判（{@link EventFactMatch} 与单点求值器同口径）。
 *
 * <p>分配契约：只读输入，不建集合、不装箱（谓词闭包除外，与求值器既有写法一致）。
 * 线程契约：无状态静态函数；事实存储本身不是线程安全的，调用方须在服务端线程使用。
 */
public final class FactWindow {

    private FactWindow() {}

    /**
     * 窗口判定：{@code typeId} 是事件类触发器类型，{@code conditions} 是其 conditions 对象。
     * 闭区间 {@code [fromTick, toTick]}；未知 / 非事件类型、结构不符 → {@code false}。
     */
    public static boolean evaluate(PlayerFacts facts, String typeId, JsonObject conditions,
                                   int fromTick, int toTick) {
        if (facts == null || typeId == null || conditions == null || fromTick > toTick) {
            return false;
        }
        return switch (typeId) {
            case "advancement" -> any(facts, FactType.ADVANCEMENT, conditions, fromTick, toTick);
            case "entity_kill" -> any(facts, FactType.ENTITY_KILL, conditions, fromTick, toTick);
            case "death" -> any(facts, FactType.DEATH, conditions, fromTick, toTick);
            case "dimension_change" -> any(facts, FactType.DIMENSION_CHANGE, conditions, fromTick, toTick);
            case "entity_interact" -> any(facts, FactType.INTERACT_ENTITY, conditions, fromTick, toTick);
            case "block_interact" -> any(facts, FactType.INTERACT_BLOCK, conditions, fromTick, toTick);
            case "item_on_interact" -> any(facts, FactType.INTERACT_BLOCK, conditions, fromTick, toTick)
                    || any(facts, FactType.INTERACT_ENTITY, conditions, fromTick, toTick);
            case "item_craft" -> any(facts, FactType.ITEM_CRAFT, conditions, fromTick, toTick);
            case "item_use" -> any(facts, FactType.ITEM_USE, conditions, fromTick, toTick);
            case "item_consume" -> any(facts, FactType.ITEM_CONSUME, conditions, fromTick, toTick);
            case "item_release" -> any(facts, FactType.ITEM_RELEASE, conditions, fromTick, toTick);
            case "item_instant_use" -> any(facts, FactType.ITEM_INSTANT_USE, conditions, fromTick, toTick);
            case "item_use_interrupt" -> any(facts, FactType.ITEM_USE_INTERRUPT, conditions, fromTick, toTick);
            case "item_pickup" -> any(facts, FactType.ITEM_PICKUP, conditions, fromTick, toTick);
            case "item_drop" -> any(facts, FactType.ITEM_DROP, conditions, fromTick, toTick);
            case "login" -> facts.lastWithin(FactType.LOGIN, "", fromTick, toTick, null) != null;
            default -> false;
        };
    }

    // ===== 单值：窗口内任一事实匹配 =====

    /** 窗口内是否存在一条该类型事实满足 {@code conditions}（事实级匹配走 {@link EventFactMatch}）。 */
    private static boolean any(PlayerFacts facts, FactType type, JsonObject conditions, int fromTick, int toTick) {
        return facts.lastWithin(type, "", fromTick, toTick,
                fact -> EventFactMatch.matches(fact, conditions)) != null;
    }
}

