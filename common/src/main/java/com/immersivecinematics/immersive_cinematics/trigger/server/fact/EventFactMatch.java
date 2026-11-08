package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.trigger.shared.GeometryConditions;

/**
 * 事实级条件匹配（不看玩家当前状态；供 Evaluators 与 H1-7 的窗口判定共用）。
 *
 * <p>只读一条事实与条件对象：{@code object} / {@code detail} / {@code scene} 与 {@code conditions}
 * 的对应字段逐项比对，不读玩家、不读世界、不查账本。调用方负责「取哪一条事实」——单值求值器取
 * 该类型最后一条事实（{@link PlayerFacts#lastOf}），{@code and} 模式取累计计数。
 *
 * <p>各类型的字段约定：
 * <ul>
 *   <li>{@link FactType#ADVANCEMENT}：{@code object} ↔ {@code conditions.advancement}</li>
 *   <li>{@link FactType#ENTITY_KILL}：{@code object} ↔ {@code conditions.entity}（单值形态；数组由调用方拆）
 *       加 {@code scene} 场景条件</li>
 *   <li>{@link FactType#DEATH}：只比 {@code scene} 场景条件</li>
 *   <li>{@link FactType#INTERACT_BLOCK} / {@link FactType#INTERACT_ENTITY}：{@code object} ↔ {@code target}，
 *       可选 {@code detail} ↔ {@code item}；条件写了 {@code target_type} 时还必须等于本类型对应的
 *       {@code "block"} / {@code "entity"}</li>
 *   <li>{@code ITEM_*} 各类型（含 PICKUP / DROP）：{@code object} ↔ {@code conditions.item}</li>
 *   <li>{@link FactType#DIMENSION_CHANGE}：只有条件带 {@code from_dimension} 时才把 {@code object}
 *       （来源维度）与之比对；不带时恒真（目标维度匹配由调用方按当前维度判断）</li>
 *   <li>{@link FactType#LOGIN}：恒真</li>
 *   <li>其他类型（播放 / 区域 / 投票等非事件事实）：{@code false}</li>
 * </ul>
 *
 * <p>分配契约：只读输入，不建集合、不装箱、不复制字符串（帧内路径安全）。线程契约：无状态静态函数。
 */
public final class EventFactMatch {

    private EventFactMatch() {}

    /** 一条事实是否满足 {@code conditions}；{@code fact == null} / {@code conditions == null} → {@code false}。 */
    public static boolean matches(Fact fact, JsonObject conditions) {
        if (fact == null || conditions == null) {
            return false;
        }
        return switch (fact.type()) {
            case ADVANCEMENT -> matchesObject(fact, conditions, "advancement");
            case ENTITY_KILL -> matchesObject(fact, conditions, "entity") && matchesScene(fact.scene(), conditions);
            case DEATH -> matchesScene(fact.scene(), conditions);
            case INTERACT_BLOCK -> matchesInteract(fact, conditions, "block");
            case INTERACT_ENTITY -> matchesInteract(fact, conditions, "entity");
            case ITEM_CRAFT, ITEM_USE, ITEM_CONSUME, ITEM_RELEASE, ITEM_USE_INTERRUPT,
                 ITEM_INSTANT_USE, ITEM_PICKUP, ITEM_DROP -> matchesObject(fact, conditions, "item");
            case DIMENSION_CHANGE -> !conditions.has("from_dimension")
                    || matchesObject(fact, conditions, "from_dimension");
            case LOGIN -> true;
            default -> false;
        };
    }

    /**
     * 场景条件匹配：{@code dimension} / {@code biome} / {@code position+radius} / {@code corner1+corner2}，
     * 语义与 {@link GeometryConditions#matchesScene} 相同。
     *
     * <p>{@code scene == null} 表示该类型不记场景快照：条件里没有任何场景字段 → 真，写了场景字段 → 假。
     */
    public static boolean matchesScene(FactScene scene, JsonObject conditions) {
        if (conditions == null) {
            return false;
        }
        if (scene == null) {
            return !hasSceneConditions(conditions);
        }
        return GeometryConditions.matchesScene(conditions, scene.dimension(), scene.biome(),
                scene.x(), scene.y(), scene.z());
    }

    // ===== Internals =====

    /** {@code conditions[field]} 是字符串且与事实的 {@code object} 匹配；字段缺失 / 非字符串 → false。 */
    private static boolean matchesObject(Fact fact, JsonObject conditions, String field) {
        JsonElement pattern = conditions.get(field);
        if (pattern == null || !pattern.isJsonPrimitive()) {
            return false;
        }
        return GeometryConditions.matchesId(fact.object(), pattern.getAsString());
    }

    /**
     * 交互条件：{@code object} ↔ {@code target}（必填），可选 {@code detail} ↔ {@code item}，
     * 可选 {@code target_type} 必须等于 {@code expectedType}（本类型隐含的方块 / 实体）。
     */
    private static boolean matchesInteract(Fact fact, JsonObject conditions, String expectedType) {
        JsonElement target = conditions.get("target");
        if (target == null || !target.isJsonPrimitive()
                || !GeometryConditions.matchesId(fact.object(), target.getAsString())) {
            return false;
        }
        JsonElement item = conditions.get("item");
        if (item != null) {
            if (!item.isJsonPrimitive()) {
                return false;
            }
            if (!GeometryConditions.matchesId(fact.detail(), item.getAsString())) {
                return false;
            }
        }
        JsonElement targetType = conditions.get("target_type");
        return targetType == null
                || (targetType.isJsonPrimitive() && expectedType.equals(targetType.getAsString()));
    }

    /** 条件里是否写了任何场景字段（与 {@link GeometryConditions#matchesScene} 识别的字段一致）。 */
    private static boolean hasSceneConditions(JsonObject conditions) {
        return conditions.has("dimension") || conditions.has("biome") || conditions.has("position")
                || (conditions.has("corner1") && conditions.has("corner2"));
    }
}
