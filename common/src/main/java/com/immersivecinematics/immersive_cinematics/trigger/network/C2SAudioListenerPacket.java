package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.AudioListenerServerState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端 → 服务端：上报/释放音频听者位置。
 * <p>
 * 相机听者模式激活期间，客户端在位置变化时发送；离开相机听者（脚本结束、
 * 没有活跃 CAMERA clip）时发送 active=false 释放，服务端回落到只按玩家位置广播。
 */
public class C2SAudioListenerPacket implements CinematicC2SPacket {

    private final boolean active;
    private final double x;
    private final double y;
    private final double z;

    public C2SAudioListenerPacket(boolean active, double x, double y, double z) {
        this.active = active;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public C2SAudioListenerPacket(FriendlyByteBuf buf) {
        this.active = buf.readBoolean();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    @Override
    public void handle(ServerPlayer player) {
        AudioListenerServerState.set(player.getUUID(), active ? new Vec3(x, y, z) : null);
    }
}
