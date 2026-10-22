package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.PlayerMoveController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家移动控制（假输入注入）：在 {@code LocalPlayer.serverAiStep} 的 HEAD 写
 * {@code input.leftImpulse/forwardImpulse}。
 *
 * 原版在 {@code super.serverAiStep()} 之后才把这两个冲量复制到 {@code xxa/zza} 再走 {@code travel()} 完整链路，
 * 所以必须抢在 HEAD（{@code input.tick} 之后）写，再晚这次写入就不会被本次 travel 读到。
 * 仅在脚本激活且 {@link PlayerMoveController} 有当前目标时启用；非激活时一字不动（走原版键盘）。
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void cinematicPlayerMove(CallbackInfo ci) {
        PlaybackInstance instance = CameraManager.INSTANCE.activeInstance();
        if (instance == null) return;
        PlayerMoveController ctrl = instance.player().getPlayerMovement();
        if (ctrl != null && ctrl.isMoving()) {
            ctrl.applyFakeInput((LocalPlayer) (Object) this);
        }
    }
}
