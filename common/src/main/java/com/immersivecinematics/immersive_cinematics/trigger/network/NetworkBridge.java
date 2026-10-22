package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * 平台网络桥接：由 Fabric/Forge 入口实现并注入 {@link NetworkHandler}。
 */
public interface NetworkBridge {
    /** 由平台实现，投递给单个玩家；不保证送达（传输层无确认）。 */
    void sendToPlayer(ServerPlayer player, CinematicS2CPacket packet);

    /** 由平台实现，从客户端投递到当前服务端；未进入游戏时会抛异常（见 {@link NetworkGuard}）。 */
    void sendToServer(CinematicC2SPacket packet);
}
