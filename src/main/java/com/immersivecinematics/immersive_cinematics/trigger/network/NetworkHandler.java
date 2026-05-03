package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import org.slf4j.Logger;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class NetworkHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ImmersiveCinematics.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        registerS2C(S2CPlayScriptPacket.class, S2CPlayScriptPacket::new, S2CPlayScriptPacket::write);
        registerS2C(S2CStopScriptPacket.class, S2CStopScriptPacket::new, S2CStopScriptPacket::write);
        registerS2C(S2CTriggerStateSyncPacket.class, S2CTriggerStateSyncPacket::new, S2CTriggerStateSyncPacket::write);
        registerC2S(C2SScriptFinishedPacket.class, C2SScriptFinishedPacket::new, C2SScriptFinishedPacket::write);
    }

    private static <T> void registerS2C(Class<T> clazz, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, FriendlyByteBuf> encoder) {
        CHANNEL.registerMessage(packetId++, clazz, encoder, decoder, (packet, context) -> {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                if (ctx.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClientPacket(packet));
                }
            });
            ctx.setPacketHandled(true);
        });
    }

    private static <T> void registerC2S(Class<T> clazz, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, FriendlyByteBuf> encoder) {
        CHANNEL.registerMessage(packetId++, clazz, encoder, decoder, (packet, context) -> {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                if (ctx.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                    handleServerPacket(packet, () -> ctx);
                }
            });
            ctx.setPacketHandled(true);
        });
    }

    @SuppressWarnings("unchecked")
    private static void handleClientPacket(Object packet) {
        if (packet instanceof S2CPlayScriptPacket p) {
            com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver.handlePlayScript(p);
        } else if (packet instanceof S2CStopScriptPacket p) {
            com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver.handleStopScript(p);
        } else if (packet instanceof S2CTriggerStateSyncPacket p) {
            com.immersivecinematics.immersive_cinematics.trigger.client.ClientTriggerStateCache.handleSync(p);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleServerPacket(Object packet, Supplier<NetworkEvent.Context> ctx) {
        if (packet instanceof C2SScriptFinishedPacket p) {
            p.handle(ctx);
        }
    }

    public static void sendToPlayer(Object packet, net.minecraft.server.level.ServerPlayer player) {
        LOGGER.info("Sending packet {} to player {}", packet.getClass().getSimpleName(), player.getName().getString());
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
