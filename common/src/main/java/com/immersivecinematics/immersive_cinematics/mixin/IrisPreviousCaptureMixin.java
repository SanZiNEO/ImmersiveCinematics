package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.ViewStateCapture;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 只读注册：捕获 Iris {@code MatrixUniforms.Previous} 实例，供每视角状态保存/恢复。
 */
@Mixin(value = MatrixUniforms.Previous.class, remap = false)
public class IrisPreviousCaptureMixin {

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ic$register(CallbackInfo ci) {
        ViewStateCapture.registerPrevious(this);
    }
}
