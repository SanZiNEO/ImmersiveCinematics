package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Camera} 写入访问器 —— 供<b>听者代理相机</b>使用
 * （{@code AudioListenerController.cameraListener()}，{@code SoundManagerMixin} 传入原版
 * {@code SoundEngine.updateSource}）。
 *
 * <p>主相机替换链退役后，原版主相机 = 玩家相机（{@code Minecraft.tick} 里
 * {@code soundManager.updateSource(gameRenderer.getMainCamera())} 拿到的就是它），所以脚本
 * {@code meta.listener = "camera"} 时必须显式造一个位于镜头位置/朝向的 {@link Camera}；
 * 原版 {@code SoundEngine.updateSource} 要求 {@code isInitialized()} 为真，故一并设置
 * {@code initialized}（与 {@code Camera.setup} 的效果一致，只是值来自模组快照）。</p>
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Invoker("setPosition")
    void ic$setPosition(double x, double y, double z);

    @Invoker("setRotation")
    void ic$setRotation(float yaw, float pitch);

    @Accessor("initialized")
    void ic$setInitialized(boolean initialized);
}
