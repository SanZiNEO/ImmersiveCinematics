package com.immersivecinematics.immersive_cinematics.fabric.mixin;

import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric 合成触发补齐。
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void immersivecinematics_onTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            ServerEventHandler.onCraftItem(sp, stack);
        }
    }
}
