package com.immersivecinematics.immersive_cinematics.fabric.mixin;

import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric 存档保存触发补齐。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "saveEverything", at = @At("RETURN"))
    private void immersivecinematics_onSaveEverything(boolean bl, boolean bl2, boolean bl3, CallbackInfoReturnable<Boolean> cir) {
        // 在原版存档保存完成后落盘触发器状态，确保 Fabric 与 Forge 一样持久化
        ServerEventHandler.onLevelSave();
    }
}
