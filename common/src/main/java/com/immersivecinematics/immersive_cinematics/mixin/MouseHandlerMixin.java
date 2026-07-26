package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.control.InputRouter;
import com.immersivecinematics.immersive_cinematics.control.InputTarget;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入处理 — 接收层 + 传输层分离。
 * <p>
 * 与 {@link KeyboardHandlerMixin} 相同架构，共用 {@link InputRouter}。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Unique
    private static InputRouter inputRouter = InputRouter.createDefault();

    // ===== 鼠标按键 =====

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long windowPointer, int button, int action,
                               int modifiers, CallbackInfo ci) {
        InputTarget target = inputRouter.routeMouseButton(button, action, modifiers);
        if (target != InputTarget.GAME) {
            ci.cancel();
        }
    }

    // ===== 鼠标滚轮 =====

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long windowPointer, double xOffset, double yOffset,
                          CallbackInfo ci) {
        InputTarget target = inputRouter.routeMouseScroll(yOffset);
        if (target != InputTarget.GAME) {
            ci.cancel();
        }
    }

    // ===== 视角移动 =====

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(CallbackInfo ci) {
        InputTarget target = inputRouter.routeTurnPlayer();
        if (target != InputTarget.GAME) {
            ci.cancel();
        }
    }

    /** 替换输入路由器实例 */
    @Unique
    private static void setInputRouter(InputRouter router) {
        inputRouter = router;
    }
}
