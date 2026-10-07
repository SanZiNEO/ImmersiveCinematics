package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneDebugDriver;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 多 lane 渲染的挂点：{@code GameRenderer.renderLevel} 的 RETURN
 * —— 主画面世界渲染之后、{@code tryTakeScreenshotIfNeeded} / {@code doEntityOutline} /
 * {@code postEffect} / GUI 之前（原型实测挂点，4 / 16 / 25 画面均正常）。
 *
 * <p>默认零差异：没有活跃 lane 时 {@link LaneRenderer#render} 第一行即返回。</p>
 */
@Mixin(GameRenderer.class)
public abstract class LaneRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ic$renderLanes(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LaneDebugDriver.tick(mc, partialTick);   // 调试驱动（未开 ICINEMATICS_QUADRANT 时直接返回）
        LaneRenderer.INSTANCE.render(mc, partialTick, nanoTime);
    }
}
