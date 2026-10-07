package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code GameRenderer} 的私有成员接入点 —— 供 {@code LaneRenderer} 复刻原版世界渲染的
 * 投影 / 光学参数（与 {@code GameRenderer.renderLevel} 同一套）：
 *
 * <pre>
 * double d = this.getFov(camera, f, true);
 * Matrix4f projection = this.getProjectionMatrix(d);
 * this.minecraft.levelRenderer.prepareCullFrustum(poseStack, camera.getPosition(),
 *         this.getProjectionMatrix(Math.max(d, this.minecraft.options.fov().get())));
 * </pre>
 *
 * <p>光照纹理走公开的 {@code GameRenderer.lightTexture()}，不需要接入。</p>
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    /** 复刻原版：{@code GameRenderer.getProjectionMatrix(double)}（含 zoom / 畸变保护）。 */
    @Invoker("getProjectionMatrix")
    Matrix4f ic$getProjectionMatrix(double fov);

    /** 复刻原版：{@code GameRenderer.getFov(Camera, float, boolean)}（走 {@code GameRendererMixin} 的 lane 分支）。 */
    @Invoker("getFov")
    double ic$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
