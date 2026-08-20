package com.immersivecinematics.immersive_cinematics.fabric.mixin;

import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric 成就触发补齐。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void immersivecinematics_onAward(Advancement advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && this.getOrStartProgress(advancement).isDone()) {
            ServerEventHandler.onPlayerAdvancement(player, advancement);
        }
    }

    @Shadow
    protected abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(Advancement advancement);
}
