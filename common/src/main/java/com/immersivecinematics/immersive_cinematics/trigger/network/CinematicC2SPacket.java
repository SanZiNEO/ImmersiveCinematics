package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端包。
 */
public interface CinematicC2SPacket extends CinematicPacket {
    /** 在服务端主线程处理；{@code player} 为发包者。 */
    void handle(ServerPlayer player);
}
