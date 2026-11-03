package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.shared.GeometryConditions;

/**
 * 窗口判定：事件类触发器在闭区间 {@code [fromTick, toTick]} 内是否满足过条件。
 *
 * <p>与单点求值器的差别只在「取哪条事实」：单点取该类型<b>最后一条</b>事实（{@link PlayerFacts#lastOf}），
 * 窗口取<b>窗口内任意一条</b>（{@link PlayerFacts#lastWithin}）。事实级匹配口径完全复用
 * {@link EventFactMatch#matches(Fact, JsonObject)}，不另立一套字段解释。
 *
 * <p><b>{@code mode=and}</b>（{@code entity_kill} / {@code item_pickup} 的数组）：每个目标模式都要在窗口内
 * 有匹配事实；按目标逐个查（保留通配语义），不是「最后一条同时满足全部」。
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
            case "entity_kill" -> entityKill(facts, conditions, fromTick, toTick);
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
            case "item_pickup" -> itemPickup(facts, conditions, fromTick, toTick);
            case "item_drop" -> any(facts, FactType.ITEM_DROP, conditions, fromTick, toTick);
            case "login" -> facts.lastWithin(FactType.LOGIN, "", fromTick, toTick, null) != null;
            default -> false;
        };
    }

    // ===== 单值 / 数组 or：窗口内任一事实匹配 =====

    /** 窗口内是否存在一条该类型事实满足 {@code conditions}（事实级匹配走 {@link EventFactMatch}）。 */
    private static boolean any(PlayerFacts facts, FactType type, JsonObject conditions, int fromTick, int toTick) {
        return facts.lastWithin(type, "", fromTick, toTick,
                fact -> EventFactMatch.matches(fact, conditions)) != null;
    }

    // ===== entity_kill：数组 and = 每个目标在窗口内都有匹配击杀 =====

    private static boolean entityKill(PlayerFacts facts, JsonObject conditions, int fromTick, int toTick) {
        JsonElement entity = conditions.get("entity");
        if (entity == null || (!entity.isJsonPrimitive() && !entity.isJsonArray())) {
            return false;
        }
        if (!entity.isJsonArray()) {
            return any(facts, FactType.ENTITY_KILL, conditions, fromTick, toTick);
        }
        JsonArray targets = entity.getAsJsonArray();
        if (targets.isEmpty()) {
            return false;
        }
        if (!"and".equals(mode(conditions))) {
            // or：窗口内任一击杀的目标命中任一模式，且该击杀时刻的场景满足场景条件
            return facts.lastWithin(FactType.ENTITY_KILL, "", fromTick, toTick,
                    fact -> matchesAny(fact.object(), targets)
                            && EventFactMatch.matchesScene(fact.scene(), conditions)) != null;
        }
        for (JsonElement target : targets) {
            if (!target.isJsonPrimitive()) {
                return false;
            }
            String pattern = target.getAsString();
            if (facts.lastWithin(FactType.ENTITY_KILL, "", fromTick, toTick,
                    fact -> GeometryConditions.matchesId(fact.object(), pattern)
                            && EventFactMatch.matchesScene(fact.scene(), conditions)) == null) {
                return false;
            }
        }
        return true;
    }

    // ===== item_pickup：数组 and = 每个物品在窗口内都捡到过 =====

    private static boolean itemPickup(PlayerFacts facts, JsonObject conditions, int fromTick, int toTick) {
        JsonElement item = conditions.get("item");
        if (item == null || (!item.isJsonPrimitive() && !item.isJsonArray())) {
            return false;
        }
        if (!item.isJsonArray()) {
            return any(facts, FactType.ITEM_PICKUP, conditions, fromTick, toTick);
        }
        JsonArray targets = item.getAsJsonArray();
        if (targets.isEmpty()) {
            return false;
        }
        if (!"and".equals(mode(conditions))) {
            return facts.lastWithin(FactType.ITEM_PICKUP, "", fromTick, toTick,
                    fact -> matchesAny(fact.object(), targets)) != null;
        }
        for (JsonElement target : targets) {
            if (!target.isJsonPrimitive()) {
                return false;
            }
            String pattern = target.getAsString();
            if (facts.lastWithin(FactType.ITEM_PICKUP, "", fromTick, toTick,
                    fact -> GeometryConditions.matchesId(fact.object(), pattern)) == null) {
                return false;
            }
        }
        return true;
    }

    // ===== Internals =====

    /** 数组模式：{@code mode} 缺省 / 非字符串 → {@code "or"}（与单点求值器同口径）。 */
    private static String mode(JsonObject conditions) {
        JsonElement mode = conditions.get("mode");
        return mode != null && mode.isJsonPrimitive() ? mode.getAsString() : "or";
    }

    /** {@code object} 是否命中数组里任一模式（通配语义同 {@link GeometryConditions#matchesId}）。 */
    private static boolean matchesAny(String object, JsonArray patterns) {
        for (JsonElement pattern : patterns) {
            if (pattern.isJsonPrimitive() && GeometryConditions.matchesId(object, pattern.getAsString())) {
                return true;
            }
        }
        return false;
    }
}
