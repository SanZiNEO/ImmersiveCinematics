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
 * 整屏合成挂点：{@code GameRenderer.renderLevel} 的 RETURN
 * —— 主画面世界渲染之后、{@code tryTakeScreenshotIfNeeded} / {@code doEntityOutline} /
 * {@code postEffect} / GUI 之前。
 *
 * <p>本挂点只做一件事：<b>MCOMP 合成</b>——{@link LaneRenderer#render} 逐 lane 渲染并把画面贴到主
 * framebuffer；lane 来源在本帧<b>世界渲染之前</b>就已注册（脚本 lane，唯一来源 {@link ScriptLaneDriver}；
 * 注册点见 {@code GameRendererMixin.onRenderFrameStart}）。</p>
 *
 * <p>RADJ（master 颜色调整）<b>已不在本挂点</b>：2026-10-08 起后移到 {@code GameRenderer.render} 内
 * 原版后处理链（RPOST）之后、GUI 之前（见 {@code GameRendererMixin.onWorldPostProcessed}，
 * 以及 plans/0.3.6/screen-color-adjust.md §4）——master 调色是世界画面的最终字，必须作用在
 * 后处理链的输出上，且不得污染 GUI 层（黑边 / 字幕 / 黑白场转场走 OVERLAY，需精确色值）。</p>
 *
 * <p>帧驱动（{@code CameraManager.onRenderFrame()}）与 lane 注册原本也在这里（本挂点开头），
 * 2026-10-07 为消除"视图中心 / 遮挡剔除决策滞后一帧"迁到 {@code GameRendererMixin.onRenderFrameStart}
 * （{@code GameRenderer.render} 的 HEAD，世界渲染之前）。</p>
 *
 * <p>默认零差异：没有活跃 lane 时 {@link LaneRenderer#render} 第一行即返回。</p>
 */
@Mixin(GameRenderer.class)
public abstract class LaneRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ic$worldRendered(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        LaneRenderer.INSTANCE.render(Minecraft.getInstance(), partialTick, nanoTime);
    }
}
