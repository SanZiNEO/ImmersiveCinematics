package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code GameRenderer} 的私有成员接入点，供 {@code LaneRenderer} 复刻原版世界渲染的投影 / 光学参数：
 * lane 的投影必须与 {@code GameRenderer.renderLevel} 同一套口径（先 {@code getFov}，再
 * {@code getProjectionMatrix}；剔除用 {@code max(fov, options.fov)} 的投影），否则 lane 的视锥与画面不匹配。
 * 光照纹理走公开的 {@code GameRenderer.lightTexture()}，不需要接入。
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
}
