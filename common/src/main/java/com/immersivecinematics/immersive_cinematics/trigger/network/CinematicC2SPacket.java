package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端包。
 */
public interface CinematicC2SPacket extends CinematicPacket {
    void handle(ServerPlayer player);
}
