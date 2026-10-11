package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 复刻原版 {@code LevelRenderer} 的私有区块可见集合刷新（{@code applyFrustum(Frustum)}）。
 *
 * 原版只在 {@code setupRender} 里按门闩调用它（触发条件：可见集合刚重建过
 * {@code needsFrustumUpdate.compareAndSet(true,false)} 或相机朝向桶
 * {@code floor(xRot/2)} / {@code floor(yRot/2)} 变化）；共享单份可见集合时该门闩对多 lane 是错的——
 * 同朝向桶的 lane 会沿用上一 pass 的可见集合（= 别条 lane 的视锥过滤结果）→ 该 lane 画面缺块。
 * 所以回落路径（per-lane 剔除不可用）的 lane pass 在自己的 {@code renderLevel} 之前按本 lane 视锥
 * 强制刷一次，见 {@code LaneRenderer.renderLane}；per-lane 生效时各 lane 有自己的门闩与可见集合，
 * 不再需要这次强制刷新。
 *
 * 与 {@link GameRendererAccessor} / {@link MinecraftAccessor} 同构：纯 {@code @Invoker} 访问器，
 * 不使用反射（本模组 mixin 插件约定）。
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    /**
     * 复刻原版：{@code LevelRenderer.applyFrustum(Frustum)} —— 清空并按给定视锥重建
     * {@code renderChunksInFrustum}（只能从渲染线程调用，原版自带线程断言）。
     */
    @Invoker("applyFrustum")
    void ic$applyFrustum(Frustum frustum);
}
