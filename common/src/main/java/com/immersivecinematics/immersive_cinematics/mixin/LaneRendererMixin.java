package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ScriptLaneDriver;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 整屏合成挂点：{@code GameRenderer.renderLevel} 的 RETURN —— 主画面世界渲染之后，
 * {@code tryTakeScreenshotIfNeeded} / {@code doEntityOutline} / {@code postEffect} / GUI 之前。
 *
 * 本挂点只做 MCOMP 合成：{@link LaneRenderer#render} 逐 lane 渲染并把画面贴到主 framebuffer。
 * lane 来源必须在本帧世界渲染之前注册（{@link ScriptLaneDriver}，见
 * {@code GameRendererMixin.onRenderFrameStart}）：视图中心在 {@code setupRender} 里读 lane 表，
 * 挂到渲染之后会让它读到上一帧的相机位置。
 *
 * master 调色（RADJ）不在这里：它是世界画面的最终字，挂在原版后处理链之后、GUI 之前
 * （{@code GameRendererMixin.onWorldPostProcessed}），既作用在合成输出上，又不污染 GUI 层
 * （黑边 / 字幕 / 黑白场转场走 OVERLAY，需要精确色值）。
 *
 * 默认零差异：没有活跃 lane 时 {@link LaneRenderer#render} 第一行即返回。
 */
@Mixin(GameRenderer.class)
public abstract class LaneRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ic$worldRendered(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        LaneRenderer.INSTANCE.render(Minecraft.getInstance(), partialTick, nanoTime);
    }
}
