package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code GameRenderer} 的私有成员接入点，供 {@code LaneRenderer} 复刻原版世界渲染的投影 / 光学参数：
 * lane 的投影必须与 {@code GameRenderer.renderLevel} 同一套口径（先 {@code getFov}，再
 * {@code getProjectionMatrix}；剔除用 {@code max(fov, options.fov)} 的投影），否则 lane 的视锥与画面不匹配。
 * 光照纹理另有一项：公开的 {@code lightTexture()} getter 只返回当前字段值，拿不到「目标视图」的
 * {@code LightTexture}；跨维度 lane 必须临时换 {@code lightTexture} 字段（{@link #ic$setLightTexture}），
 * 因为地形 / 实体走 {@code RenderStateShard.LightmapStateShard} → 该字段，{@code renderLevel} 的参数只覆盖天气 / 粒子。
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    /** {@code GameRenderer.getProjectionMatrix(double)}（含 zoom / 畸变保护）。 */
    @Invoker("getProjectionMatrix")
    Matrix4f ic$getProjectionMatrix(double fov);

    /**
     * {@code GameRenderer.getFov(Camera, float, boolean)}；
     * lane 相机走 {@code GameRendererMixin} 的 lane 分支。
     */
    @Invoker("getFov")
    double ic$getFov(Camera camera, float partialTick, boolean useFovSetting);

    /** {@code renderHand} 开关：lane pass 内关掉（Iris 的手部渲染在 renderLevel 内，每 lane 都会跑一次）。 */
    @Accessor("renderHand")
    boolean ic$getRenderHand();

    /**
     * 换光照贴图指向（跨维度 lane pass 期间临时切到目标视图的 {@code LightTexture}）；调用方负责成对还原。
     * 原版字段是 {@code private final}，{@code @Mutable} 让 mixin 生成 setter 时剥掉 final。
     */
    @Accessor("lightTexture")
    @Mutable
    void ic$setLightTexture(LightTexture lightTexture);
}
