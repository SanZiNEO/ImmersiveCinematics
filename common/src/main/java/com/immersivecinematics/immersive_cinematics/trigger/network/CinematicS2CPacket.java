package com.immersivecinematics.immersive_cinematics.trigger.network;

/**
 * 服务端 → 客户端包。
 */
public interface CinematicS2CPacket extends CinematicPacket {
    void handle();
}
