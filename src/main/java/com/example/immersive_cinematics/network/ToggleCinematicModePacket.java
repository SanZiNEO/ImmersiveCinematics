package com.example.immersive_cinematics.network;

import com.example.immersive_cinematics.handler.CinematicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 切换电影模式数据包
 * 从服务器端发送到客户端，用于通知客户端切换电影模式
 */
public class ToggleCinematicModePacket {

    public ToggleCinematicModePacket() {
        // 空构造函数，用于网络解码
    }

    /**
     * 编码数据包到字节流
     */
    public static void encode(ToggleCinematicModePacket message, FriendlyByteBuf buffer) {
        // 这个数据包不需要额外数据
    }

    /**
     * 从字节流解码数据包
     */
    public static ToggleCinematicModePacket decode(FriendlyByteBuf buffer) {
        return new ToggleCinematicModePacket();
    }

    /**
     * 在客户端处理数据包
     */
    public static void handle(ToggleCinematicModePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 在客户端线程上执行
            CinematicManager.getInstance().toggleCinematic();
        });
        context.setPacketHandled(true);
    }
}