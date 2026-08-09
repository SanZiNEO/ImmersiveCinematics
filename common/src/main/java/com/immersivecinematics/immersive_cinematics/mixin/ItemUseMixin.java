package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 使用状态机（LivingEntity）注入点：
 * <ul>
 *   <li>{@code completeUsingItem} — 仅在 {@code --useItemRemaining <= 0 && !useOnRelease()} 时调用，
 *       天然只有食物/药水等"用尽"类物品会走到这里（吃一半松手不会）。</li>
 *   <li>{@code releaseUsingItem} — 松手路径。弓/弩/三叉戟/望远镜（UseAnim 判定）为"释放"，
 *       其余（吃一半/喝一半/普通物品/自定义 UseAnim）归为"中断"。</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class ItemUseMixin {

    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void onCompleteUsing(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) return;
        if (self instanceof ServerPlayer sp && !self.getUseItem().isEmpty()) {
            Evaluators.UseItemTracker.recordConsumed(sp, self.getUseItem());
            TriggerEngine.INSTANCE.onGameEvent("item_consume", sp);
        }
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void onReleaseUsing(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) return;
        if (self instanceof ServerPlayer sp && !self.getUseItem().isEmpty()) {
            UseAnim anim = self.getUseItem().getUseAnimation();
            if (anim == UseAnim.BOW || anim == UseAnim.SPEAR
                    || anim == UseAnim.CROSSBOW || anim == UseAnim.SPYGLASS) {
                Evaluators.UseItemTracker.recordReleased(sp, self.getUseItem());
                TriggerEngine.INSTANCE.onGameEvent("item_release", sp);
            } else {
                TriggerEngine.INSTANCE.onGameEvent("item_use_interrupt", sp);
            }
        }
    }
}
