package com.immersivecinematics.immersive_cinematics.fabric.mixin;

import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric 拾取触发补齐。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;awardStat(Lnet/minecraft/stats/Stat;I)V",
            shift = At.Shift.BEFORE))
    private void immersivecinematics_onPickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            ItemStack stack = ((ItemEntity) (Object) this).getItem();
            if (!stack.isEmpty()) {
                ServerEventHandler.onPickupItem(sp, stack.copy());
            }
        }
    }
}
