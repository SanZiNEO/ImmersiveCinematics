package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CinematicCameraEntity;
import com.immersivecinematics.immersive_cinematics.handler.CinematicCameraHandler;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    private Entity entity;

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(CallbackInfo ci) {
        CinematicCameraHandler handler = CinematicCameraHandler.getInstance();
        
        if (handler.isActive() && handler.getCameraEntity() != null) {
            CinematicCameraEntity camera = handler.getCameraEntity();
            
            // 设置相机位置
            setPosition(camera.position().x, camera.position().y, camera.position().z);
            
            // 设置相机旋转
            setRotation(camera.getYaw(), camera.getPitch());
            
            // 标记为已设置
            ci.cancel();
        }
    }

    @Inject(method = "getEntity", at = @At("HEAD"), cancellable = true)
    private void onGetEntity(CallbackInfoReturnable<Entity> cir) {
        CinematicCameraHandler handler = CinematicCameraHandler.getInstance();
        
        if (handler.isActive() && handler.getCameraEntity() != null) {
            cir.setReturnValue(handler.getCameraEntity());
        }
    }
}
