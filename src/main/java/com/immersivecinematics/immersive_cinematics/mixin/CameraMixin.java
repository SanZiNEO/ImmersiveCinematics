package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter level, Entity entity, boolean detached,
                         boolean mirror, float partialTick, CallbackInfo ci) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            Vec3 pos = mgr.getPath().getPosition();
            setPosition(pos.x, pos.y, pos.z);
            setRotation(mgr.getProperties().getYaw(), mgr.getProperties().getPitch());
            ci.cancel();  // setRotation 内部自动计算 forwards/up/left 向量
        }
    }

    /**
     * 返回玩家 Entity，避免渲染管线 NPE
     * 原版 Camera.setup() 中 this.entity = entity，我们 cancel 了 setup 导致 entity 字段为 null
     * 渲染管线多处调用 getEntity()，返回 null 会崩溃
     */
    @Inject(method = "getEntity", at = @At("HEAD"), cancellable = true)
    private void onGetEntity(CallbackInfoReturnable<Entity> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue(Minecraft.getInstance().player);
        }
    }

    /**
     * 返回 true 使 Minecraft 认为相机处于"分离"（第三人称）模式
     * 效果：
     * 1. 渲染玩家身体模型（第一人称不渲染玩家自己）
     * 2. 隐藏第一人称手臂渲染
     * 3. 不影响 HUD，HUD 需要单独处理（GuiMixin）
     */
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue(true);
        }
    }
}
