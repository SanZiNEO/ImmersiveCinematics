package com.immersivecinematics.immersive_cinematics.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把 {@code Minecraft.mainRenderTarget} 临时指向当前 lane 的离屏缓冲（{@code LaneRenderer} 渲染期间）。
 *
 * 目的：渲染期间原版代码里所有 {@code getMainRenderTarget()} 引用（实体段 / 粒子 / 云 / 天气的 target
 * 切换与回绑）都落在该 FBO 上，viewport 被重置时也只会重置到 FBO 自己的尺寸，从而保证
 * 「整尺寸渲染到 FBO，再交给合成层」这条路不被打断。
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    /** 换主画面指向；调用方负责成对还原。 */
    @Accessor("mainRenderTarget")
    @Mutable
    void ic$setMainRenderTarget(RenderTarget target);

    /**
     * 换世界渲染器指向（跨维度 lane pass 期间临时切到目标视图的渲染器）；调用方负责成对还原。
     * 原版字段是 {@code public final}，{@code @Mutable} 让 mixin 生成 setter 时剥掉 final。
     */
    @Accessor("levelRenderer")
    @Mutable
    void ic$setLevelRenderer(LevelRenderer renderer);
}
