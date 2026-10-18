package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.ViewStateCapture;
import net.irisshaders.iris.uniforms.CameraUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 只读注册：捕获 Iris {@code CameraPositionTracker} 实例，供每视角状态保存/恢复。
 */
@Mixin(value = CameraUniforms.CameraPositionTracker.class, remap = false)
public class IrisCameraTrackerCaptureMixin {

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ic$register(CallbackInfo ci) {
        ViewStateCapture.registerTracker(this);
    }
}
