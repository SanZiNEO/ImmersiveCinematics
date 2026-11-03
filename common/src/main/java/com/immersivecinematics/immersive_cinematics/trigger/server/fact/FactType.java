package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import java.util.HashMap;
import java.util.Map;

/**
 * 事实类型：声明持久性与保留上限。
 *
 * <p>{@link Durability#PERSISTENT} 的事实随存档写入 SNBT（版本 2），{@link Durability#SESSION}
 * 的事实只存在于内存、玩家退出即随内存表丢弃。{@link #maxRetained()} 是该类型保留集的条数上限，
 * {@code 0} 表示不按条数淘汰（例如 {@link #SKIP_VOTE} 由实例结束时显式清理）。
 *
 * <p>{@link #id()} 是 SNBT 的稳定键（snake_case）；重命名 Java 常量不改变存档格式。
 */
public enum FactType {
    PLAYBACK_STARTED(Durability.PERSISTENT, 256, "playback_started"),
    PLAYBACK_FINISHED(Durability.PERSISTENT, 256, "playback_finished"),
    TRIGGER_FIRED(Durability.PERSISTENT, 1024, "trigger_fired"),

    REGION_ENTERED(Durability.SESSION, 512, "region_entered"),
    REGION_LEFT(Durability.SESSION, 512, "region_left"),
    /** 0 = 不按条数淘汰，随实例结束由 {@link PlayerFacts#removeInstance(String, String)} 清理。 */
    SKIP_VOTE(Durability.SESSION, 0, "skip_vote"),
    LOGIN(Durability.SESSION, 16, "login"),
    ENTITY_KILL(Durability.SESSION, 256, "entity_kill"),
    DEATH(Durability.SESSION, 64, "death"),
    ADVANCEMENT(Durability.SESSION, 256, "advancement"),
    DIMENSION_CHANGE(Durability.SESSION, 64, "dimension_change"),
    INTERACT_BLOCK(Durability.SESSION, 256, "interact_block"),
    INTERACT_ENTITY(Durability.SESSION, 256, "interact_entity"),
    ITEM_CRAFT(Durability.SESSION, 256, "item_craft"),
    ITEM_USE(Durability.SESSION, 128, "item_use"),
    ITEM_CONSUME(Durability.SESSION, 128, "item_consume"),
    ITEM_RELEASE(Durability.SESSION, 128, "item_release"),
    ITEM_USE_INTERRUPT(Durability.SESSION, 64, "item_use_interrupt"),
    ITEM_INSTANT_USE(Durability.SESSION, 128, "item_instant_use"),
    ITEM_PICKUP(Durability.SESSION, 512, "item_pickup"),
    ITEM_DROP(Durability.SESSION, 256, "item_drop");

    /** 事实的持久性：跟存档还是跟会话。 */
    public enum Durability {
        SESSION,
        PERSISTENT
    }

    private static final Map<String, FactType> BY_ID = new HashMap<>();

    static {
        for (FactType type : values()) {
            BY_ID.put(type.id, type);
        }
    }

    private final Durability durability;
    private final int maxRetained;
    private final String id;

    FactType(Durability durability, int maxRetained, String id) {
        this.durability = durability;
        this.maxRetained = maxRetained;
        this.id = id;
    }

    public Durability durability() {
        return durability;
    }

    /** 保留集条数上限；{@code 0} = 不按条数淘汰。 */
    public int maxRetained() {
        return maxRetained;
    }

    /** SNBT 中的稳定字符串键（snake_case）。 */
    public String id() {
        return id;
    }

    /** 按稳定 id 查类型；未知返回 {@code null}（由调用方跳过）。 */
    public static FactType byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }
}
