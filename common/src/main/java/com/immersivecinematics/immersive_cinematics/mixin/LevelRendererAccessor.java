package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 复刻原版 {@code LevelRenderer} 的私有区块可见集合刷新（{@code applyFrustum(Frustum)}）。
 *
 * <p>原版只在 {@code setupRender} 里按门闩调用它（触发条件：可见集合刚重建过
 * {@code needsFrustumUpdate.compareAndSet(true,false)} 或相机朝向桶
 * {@code floor(xRot/2)} / {@code floor(yRot/2)} 变化）；门闩对多 lane 是错的——<b>同朝向桶的 lane
 * 会沿用上一 pass 的可见集合</b>（= 别条 lane 的视锥过滤结果）→ 该 lane 画面缺块。所以 lane pass
 * 在自己的 {@code renderLevel} 之前按本 lane 视锥强制刷一次，见 {@code LaneRenderer.renderLane}。</p>
 *
 * <p>与 {@link GameRendererAccessor} / {@link MinecraftAccessor} 同构：纯 {@code @Invoker} 访问器，
 * 不使用反射（本模组 mixin 插件约定）。</p>
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
