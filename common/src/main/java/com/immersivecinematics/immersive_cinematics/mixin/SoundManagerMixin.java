package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 音频听者：把原版 {@code SoundEngine} 的听者相机换成模组代理。
 *
 * <p>原版 {@code Minecraft.tick} 传的是<b>主相机</b>（{@code gameRenderer.getMainCamera()}）。主相机替换链
 * 退役后主相机 = 原版玩家相机，所以两种听者模式都要显式提供代理：</p>
 * <ul>
 *   <li>{@code listener = "camera"} → {@link AudioListenerController#cameraListener()}
 *       （位置/朝向 = 顶层实例顶层活跃 clip 的相机状态）；</li>
 *   <li>{@code listener = "player"}（默认）→ {@link AudioListenerController#playerCamera()}
 *       （玩家视角代理，与原实现一致）。</li>
 * </ul>
 * <p>无活跃过场时原样放行（零差异）。</p>
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @ModifyArg(method = "updateSource", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundEngine;updateSource(Lnet/minecraft/client/Camera;)V"), index = 0)
    private Camera immersivecinematics_listenerCamera(Camera camera) {
        if (AudioListenerController.isCameraListener()) {
            return AudioListenerController.cameraListener();
        }
        if (AudioListenerController.shouldOverride()) {
            return AudioListenerController.playerCamera();
        }
        return camera;
    }
}
