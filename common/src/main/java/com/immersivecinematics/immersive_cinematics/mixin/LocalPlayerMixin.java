package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.PlayerMoveController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家移动控制（假输入注入）：
 * 注入 {@code LocalPlayer.serverAiStep} @HEAD——
 * 原版该方法在 {@code super.serverAiStep()} 后将 {@code input.leftImpulse/forwardImpulse}
 * 复制到 {@code xxa/zza} 走 {@code travel()} 完整链路。我们在 @HEAD（input.tick 之后）写入冲量，
 * 实现"朝目标点走"；仅在脚本激活且 PlayerMoveController 有当前目标时启用。非激活时一字不动（走原版键盘）。
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void cinematicPlayerMove(CallbackInfo ci) {
        if (!CameraManager.INSTANCE.isActive()) return;
        PlayerMoveController ctrl = CameraManager.INSTANCE.getScriptPlayer().getPlayerMovement();
        if (ctrl != null && ctrl.isMoving()) {
            ctrl.applyFakeInput((LocalPlayer) (Object) this);
        }
    }
}
