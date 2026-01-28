package com.example.immersive_cinematics.network;

import com.example.immersive_cinematics.director.TimelineProcessor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 镜头脚本触发数据包
 * 从服务器端发送到客户端，用于通知客户端触发特定镜头脚本
 */
public class TriggerCameraScriptPacket {

    private final String scriptName;

    public TriggerCameraScriptPacket(String scriptName) {
        this.scriptName = scriptName;
    }

    /**
     * 编码数据包到字节流
     */
    public static void encode(TriggerCameraScriptPacket message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.scriptName);
    }

    /**
     * 从字节流解码数据包
     */
    public static TriggerCameraScriptPacket decode(FriendlyByteBuf buffer) {
        String scriptName = buffer.readUtf();
        return new TriggerCameraScriptPacket(scriptName);
    }

    /**
     * 在客户端处理数据包
     */
    public static void handle(TriggerCameraScriptPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 在客户端线程上执行
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                TimelineProcessor.getInstance().startCameraScript(
                        net.minecraft.client.Minecraft.getInstance().player, message.scriptName);
            }
        });
        context.setPacketHandled(true);
    }

    public String getScriptName() {
        return scriptName;
    }
}