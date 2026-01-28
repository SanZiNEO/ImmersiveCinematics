package com.example.immersive_cinematics.network;

import com.example.immersive_cinematics.ImmersiveCinematics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通信处理器
 * 负责注册和发送数据包
 */
public class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ImmersiveCinematics.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        // 注册维度切换触发数据包
        INSTANCE.registerMessage(packetId++, ToggleCinematicModePacket.class,
                ToggleCinematicModePacket::encode,
                ToggleCinematicModePacket::decode,
                ToggleCinematicModePacket::handle);

        // 注册镜头脚本触发数据包
        INSTANCE.registerMessage(packetId++, TriggerCameraScriptPacket.class,
                TriggerCameraScriptPacket::encode,
                TriggerCameraScriptPacket::decode,
                TriggerCameraScriptPacket::handle);
    }

    /**
     * 发送给所有在线玩家
     */
    public static <MSG> void sendToAll(MSG message) {
        INSTANCE.send(PacketDistributor.ALL.noArg(), message);
    }

    /**
     * 发送给特定玩家
     */
    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}