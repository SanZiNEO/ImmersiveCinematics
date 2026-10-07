package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneDebugDriver;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.client.lane.ScriptLaneDriver;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustPass;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 整屏后处理挂点：{@code GameRenderer.renderLevel} 的 RETURN
 * —— 主画面世界渲染之后、{@code tryTakeScreenshotIfNeeded} / {@code doEntityOutline} /
 * {@code postEffect} / GUI 之前（原型实测挂点，4 / 16 / 25 画面均正常）。
 *
 * <p>本挂点按架构图顺序做两件事（<b>顺序必须在同一次注入里保证</b>，不能拆成两个 Mixin ——
 * 同一 RETURN 上多个 Mixin 的注入先后不由我们控制）：</p>
 * <ol>
 *   <li><b>MCOMP 合成</b>：{@link LaneRenderer#render} 逐 lane 渲染并把画面贴到主 framebuffer；
 *       lane 来源在本帧<b>世界渲染之前</b>就已注册（脚本 lane {@link ScriptLaneDriver} 优先，本帧没有
 *       脚本 lane 时调试驱动 {@link LaneDebugDriver} 照常工作——两者不会同时写 lane；注册点见
 *       {@code GameRendererMixin.onRenderFrameStart}）。</li>
 *   <li><b>RADJ 颜色调整</b>：{@link ColorAdjustPass#render} 在<b>合成输出</b>上开一次全屏 pass
 *       （master 标量组），即 MCOMP 之后、原版 RPOST 与 GUI 之前
 *       （见 plans/0.3.6/screen-color-adjust.md §4）。</li>
 * </ol>
 *
 * <p>帧驱动（{@code CameraManager.onRenderFrame()}）与 lane 注册原本也在这里（本挂点开头），
 * 2026-10-07 为消除"视图中心 / 遮挡剔除决策滞后一帧"迁到 {@code GameRendererMixin.onRenderFrameStart}
 * （{@code GameRenderer.render} 的 HEAD，世界渲染之前）。</p>
 *
 * <p>默认零差异：没有活跃 lane 时 {@link LaneRenderer#render} 第一行即返回；没有活跃的颜色调整
 * （没人发布 / 参数恒等）时 {@link ColorAdjustPass#render} 第一行即返回。</p>
 */
@Mixin(GameRenderer.class)
public abstract class LaneRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ic$worldRendered(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LaneRenderer.INSTANCE.render(mc, partialTick, nanoTime);
        ColorAdjustPass.render(mc);                  // 必须排在 lane 合成之后（作用在合成输出上）
    }
}
