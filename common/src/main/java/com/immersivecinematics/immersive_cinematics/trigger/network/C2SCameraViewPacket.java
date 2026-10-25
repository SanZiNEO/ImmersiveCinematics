package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraViewManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 跨维度视图流式加载上报（C2S）：客户端每条「目标维度 lane」一个视图。
 *
 * 字段：视图 id（{@code captureId@维度}）+ 目标维度 + 相机眼位 + 是否仍活跃 + 加载半径。
 * 服务端按 {@code (玩家, 视图 id)} 维护独立的区块 / 实体流；{@code active=false} 表示该视图已消失，
 * 服务端释放该视图的区块、实体与 ticket。位置包与 START 同包，服务端按区块变化移动 ticket。
 */
public class C2SCameraViewPacket implements CinematicC2SPacket {

    private final String viewId;
    private final ResourceLocation dimension;
    private final double x;
    private final double y;
    private final double z;
    private final boolean active;
    private final int radius;

    public C2SCameraViewPacket(String viewId, ResourceLocation dimension,
                               double x, double y, double z, boolean active, int radius) {
        this.viewId = viewId;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.active = active;
        this.radius = radius;
    }

    public C2SCameraViewPacket(FriendlyByteBuf buf) {
        this.viewId = buf.readUtf();
        this.dimension = buf.readResourceLocation();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.active = buf.readBoolean();
        this.radius = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(viewId);
        buf.writeResourceLocation(dimension);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeBoolean(active);
        buf.writeVarInt(radius);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（ticket / 区块 / 实体流必须主线程）
        CameraViewManager.INSTANCE.handle(this, player);
    }

    public String viewId() {
        return viewId;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public boolean active() {
        return active;
    }

    public int radius() {
        return radius;
    }
}
