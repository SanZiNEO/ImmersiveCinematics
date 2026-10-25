package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ClientPacketListener.level} 的读写口：服务端维度元数据包接入后，客户端需要按「连接 + 当前世界」
 * 组合出目标维度的 {@code ClientLevel}（字段本身是 private、非 final，mixin setter 需要 {@code @Mutable}
 * 才能在映射变化时保持可写）。
 *
 * 当前切片只加访问器、不接线：跨维度视图的创建走 {@code DimensionViews.acquire}。
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {

    /** 当前连接绑定的客户端世界（断线后为 {@code null}）。 */
    @Accessor("level")
    ClientLevel ic$getLevel();

    /** 换连接绑定的客户端世界；调用方负责还原 / 生命周期。 */
    @Accessor("level")
    @Mutable
    void ic$setLevel(ClientLevel level);
}
