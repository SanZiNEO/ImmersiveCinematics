package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraFakePlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 隐藏相机假人的“加入游戏”聊天广播和服务端 logged in 日志。
 * 通过 {@code instanceof CameraFakePlayer} 判断，不依赖假人名字字符串。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Redirect(
            method = "placeNewPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
                    ordinal = 0
            )
    )
    private void immersivecinematics_hideFakeJoin(PlayerList instance, Component message, boolean overlay,
                                                  Connection connection, ServerPlayer player) {
        if (!(player instanceof CameraFakePlayer)) {
            instance.broadcastSystemMessage(message, overlay);
        }
    }

    @Redirect(
            method = "placeNewPlayer",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;info(Ljava/lang/String;[Ljava/lang/Object;)V",
                    ordinal = 0
            )
    )
    private void immersivecinematics_hideFakeLoggedIn(Logger logger, String message, Object[] args,
                                                      Connection connection, ServerPlayer player) {
        if (!(player instanceof CameraFakePlayer)) {
            logger.info(message, args);
        }
    }
}
