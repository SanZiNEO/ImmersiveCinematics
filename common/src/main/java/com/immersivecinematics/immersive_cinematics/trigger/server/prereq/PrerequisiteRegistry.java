package com.immersivecinematics.immersive_cinematics.trigger.server.prereq;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前置条件类型注册表。
 * <p>
 * 内置条件由 {@code BuiltinPrerequisites} 注册；其他模组可在自己的
 * 初始化阶段调用 {@link #register(String, TriggerPrerequisite)} 添加自定义类型。
 */
public final class PrerequisiteRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Prerequisite");

    private static final Map<String, TriggerPrerequisite> PREREQUISITES = new ConcurrentHashMap<>();

    private PrerequisiteRegistry() {}

    /**
     * 注册自定义前置条件类型。重复注册时后注册者覆盖先注册者。
     *
     * @param type  类型标识，建议使用 {@code modid:name} 避免冲突
     * @param prereq 求值器
     */
    public static void register(String type, TriggerPrerequisite prereq) {
        if (type == null || type.isBlank() || prereq == null) return;
        PREREQUISITES.put(type, prereq);
        LOGGER.info("Registered prerequisite type: {}", type);
    }

    public static TriggerPrerequisite get(String type) {
        return type == null ? null : PREREQUISITES.get(type);
    }

    public static boolean has(String type) {
        return type != null && PREREQUISITES.containsKey(type);
    }

    /**
     * 求值一个前置条件。未知类型默认不满足，并记录一次警告。
     */
    public static boolean evaluate(String type, ServerPlayer player, JsonObject data) {
        TriggerPrerequisite prereq = get(type);
        if (prereq == null) {
            LOGGER.warn("Unknown prerequisite type '{}' (player={}) — prerequisite treated as unmet", type, player != null ? player.getName().getString() : "?");
            return false;
        }
        try {
            return prereq.evaluate(player, data != null ? data : new JsonObject());
        } catch (Exception e) {
            LOGGER.error("Failed to evaluate prerequisite '{}' for player {}", type,
                    player != null ? player.getName().getString() : "?", e);
            return false;
        }
    }
}
