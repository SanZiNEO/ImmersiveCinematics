package com.example.immersive_cinematics.mixin;

import com.example.immersive_cinematics.handler.CinematicManager;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Gui.class, priority = 999)
public class GuiMixin {

    /**
     * 当处于电影模式时，完全取消 GUI 渲染，包括准星和各种覆盖层
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        if (CinematicManager.getInstance().isCinematicActive()) {
            ci.cancel();
        }
    }

    /**
     * 专门拦截十字准星渲染
     */
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void onRenderCrosshair(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (CinematicManager.getInstance().isCinematicActive()) {
            ci.cancel();
        }
    }
}
