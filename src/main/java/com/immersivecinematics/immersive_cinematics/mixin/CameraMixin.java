package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CinematicCameraEntity;
import com.immersivecinematics.immersive_cinematics.handler.CinematicCameraHandler;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double p_90585_, double p_90586_, double p_90587_);

    @Shadow
    protected abstract void setRotation(float p_90573_, float p_90574_);
    
    @Shadow
    private Entity entity;

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter p_90576_, Entity p_90577_, boolean p_90578_, boolean p_90579_, float p_90580_, CallbackInfo ci) {
        CinematicCameraHandler handler = CinematicCameraHandler.getInstance();
        
        if (handler.isActive() && handler.getCameraEntity() != null) {
            CinematicCameraEntity camera = handler.getCameraEntity();
            
            // 直接设置相机位置和旋转，避免递归调用
            setPosition(camera.getX(), camera.getY(), camera.getZ());
            setRotation(camera.getYRot(), camera.getXRot());
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
