package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraFakePlayer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机假人是纯占位实体，绝不能作为“其他玩家”被真实玩家客户端跟踪：
 * 拦截 {@link ServerEntity#addPairing} / {@link ServerEntity#removePairing}，
 * 不向任何真实玩家发送 AddPlayer/RemoveEntities，也不加入 seenBy 集合。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "addPairing", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_skipFakePlayerAddPairing(ServerPlayer player, CallbackInfo ci) {
        if (this.entity instanceof CameraFakePlayer) {
            ci.cancel();
        }
    }

    @Inject(method = "removePairing", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_skipFakePlayerRemovePairing(ServerPlayer player, CallbackInfo ci) {
        if (this.entity instanceof CameraFakePlayer) {
            ci.cancel();
        }
    }
}
