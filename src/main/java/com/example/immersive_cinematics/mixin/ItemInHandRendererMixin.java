package com.example.immersive_cinematics.mixin;

import com.example.immersive_cinematics.handler.CinematicManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    // 隐藏手臂渲染
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void onRenderHandsWithItems(float partialTick, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LocalPlayer player, int packedLight, CallbackInfo ci) {
        if (CinematicManager.getInstance().isCinematicActive()) {
            ci.cancel();
        }
    }
}
