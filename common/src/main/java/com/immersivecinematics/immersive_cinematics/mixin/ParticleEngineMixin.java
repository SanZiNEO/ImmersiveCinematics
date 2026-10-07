package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * lane 内容开关：粒子。原版 {@code LevelRenderer.renderLevel} 在 translucent 段前后各调一次
 * {@code particleEngine.render(...)}——两处都在这里统一拦掉，避免逐调用点 {@code @Redirect}。
 *
 * <p>非 lane pass 恒放行（主画面不受内容开关影响）。</p>
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ic$laneContentSwitch(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                      LightTexture lightTexture, Camera camera, float partialTick, CallbackInfo ci) {
        if (!LaneRenderer.shouldRenderParticles()) {
            ci.cancel();
        }
    }
}
