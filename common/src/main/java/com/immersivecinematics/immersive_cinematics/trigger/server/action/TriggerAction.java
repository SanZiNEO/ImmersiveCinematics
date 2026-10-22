package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import net.minecraft.server.level.ServerPlayer;

/** 触发器命中后执行的动作；在服务端主线程调用，参数为触发者。 */
public interface TriggerAction {
    /** 对触发者执行该动作。 */
    void execute(ServerPlayer player);
}
