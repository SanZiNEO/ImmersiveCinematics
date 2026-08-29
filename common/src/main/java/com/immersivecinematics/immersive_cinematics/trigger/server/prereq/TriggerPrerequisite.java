package com.immersivecinematics.immersive_cinematics.trigger.server.prereq;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/**
 * 前置条件求值接口。
 * <p>
 * 其他模组可以通过 {@link PrerequisiteRegistry#register(String, TriggerPrerequisite)}
 * 注册自定义前置条件类型，并在脚本触发器的 {@code requires} 中使用对象语法：
 * <pre>
 * "requires": [
 *   { "type": "my_mod:custom", "value": 42 }
 * ]
 * </pre>
 */
@FunctionalInterface
public interface TriggerPrerequisite {

    /**
     * 判断该前置条件是否满足。
     *
     * @param player 触发者
     * @param data   条件参数（来自 JSON requires 数组中的对象）
     * @return true = 满足，false = 未满足（该触发器暂不解锁）
     */
    boolean evaluate(ServerPlayer player, JsonObject data);
}
