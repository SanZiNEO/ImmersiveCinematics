package com.immersivecinematics.immersive_cinematics.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 四象限原型（一次性，测完即删）：把 {@code Minecraft.mainRenderTarget} 临时指向
 * 原型相机的离屏缓冲。
 *
 * <p>目的：渲染期间原版代码里所有 {@code getMainRenderTarget()} 引用（实体段/粒子/云/天气
 * 的 target 切换与回绑）都落在该 FBO 上，viewport 被重置时也只会重置到 FBO 自己的尺寸，
 * 从而保证"整尺寸渲染到 FBO，再缩放贴进象限"这条路不被打断。</p>
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("mainRenderTarget")
    @Mutable
    void ic$setMainRenderTarget(RenderTarget target);
}
