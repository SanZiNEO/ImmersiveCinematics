package com.immersivecinematics.immersive_cinematics.trigger.network;

/**
 * 服务端 → 客户端包。
 */
public interface CinematicS2CPacket extends CinematicPacket {
    /** 在客户端主线程处理；平台网络层保证线程模型。 */
    void handle();
}
