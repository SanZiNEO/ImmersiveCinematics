package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import net.minecraft.server.level.ServerPlayer;

public interface TriggerAction {
    void execute(ServerPlayer player);
}
