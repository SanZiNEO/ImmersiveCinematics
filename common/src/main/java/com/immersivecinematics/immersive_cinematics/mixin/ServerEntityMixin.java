package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterDiagnostics;
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
 * 实体配对诊断（C6）：{@code addPairing} / {@code removePairing} 是原版「下发 / 遗忘」的唯一成对入口。
 * 只在 HEAD 计数，不取消、不改写任何行为；计数只归属于当前存在相机中心诊断会话的玩家
 * （见 {@link CameraCenterDiagnostics#onPair}）。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "addPairing", at = @At("HEAD"))
    private void immersivecinematics_countPairAdd(ServerPlayer player, CallbackInfo ci) {
        CameraCenterDiagnostics.onPair(this.entity, player, true);
    }

    @Inject(method = "removePairing", at = @At("HEAD"))
    private void immersivecinematics_countPairRemove(ServerPlayer player, CallbackInfo ci) {
        CameraCenterDiagnostics.onPair(this.entity, player, false);
    }
}
