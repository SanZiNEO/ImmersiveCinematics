package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Camera} 写入访问器，供听者代理相机使用（{@code AudioListenerController.cameraListener()}，
 * 由 {@code SoundManagerMixin} 传给原版 {@code SoundEngine.updateSource}）。
 *
 * 原版 {@code Minecraft.tick} 传的是玩家相机（{@code getMainCamera()}），所以 {@code meta.listener = "camera"} 时必须
 * 显式造一个位于镜头位置 / 朝向的 {@link Camera}；{@code SoundEngine.updateSource} 要求
 * {@code isInitialized()} 为真，故一并设置 {@code initialized}（与 {@code Camera.setup} 的效果一致，
 * 只是值来自模组快照）。
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    /** 写相机位置（世界坐标，已是眼高）。 */
    @Invoker("setPosition")
    void ic$setPosition(double x, double y, double z);

    /** 写相机朝向（度）。 */
    @Invoker("setRotation")
    void ic$setRotation(float yaw, float pitch);

    /** 置 {@code initialized}：绕开 {@code setup()} 也必须满足 {@code isInitialized()}。 */
    @Accessor("initialized")
    void ic$setInitialized(boolean initialized);
}
