package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * 平台网络桥接：由 Fabric/Forge 入口实现并注入 {@link NetworkHandler}。
 */
public interface NetworkBridge {
    void sendToPlayer(ServerPlayer player, CinematicS2CPacket packet);
    void sendToServer(CinematicC2SPacket packet);
}
