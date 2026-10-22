package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 平台无关网络包。
 * 由 Fabric/Forge 平台网络层负责编码/解码/分发。
 */
public interface CinematicPacket {
    void write(FriendlyByteBuf buf);
}
