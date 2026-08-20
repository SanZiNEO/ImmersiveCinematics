package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.control.FlightController;
import com.immersivecinematics.immersive_cinematics.control.InputRouter;
import com.immersivecinematics.immersive_cinematics.control.InputTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

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

    // ===== 鼠标原始移动（飞行取景软回中 + delta） =====

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void onMove(long windowPointer, double x, double y, CallbackInfo ci) {
        if (FlightController.INSTANCE.isActive()) {
            double dx = x - this.xpos;
            double dy = y - this.ypos;
            FlightController.INSTANCE.onMouseMove(dx, dy);

            long win = Minecraft.getInstance().getWindow().getWindow();
            int cw = Minecraft.getInstance().getWindow().getScreenWidth();
            int ch = Minecraft.getInstance().getWindow().getScreenHeight();
            GLFW.glfwSetCursorPos(win, cw / 2.0, ch / 2.0);
            this.xpos = cw / 2.0;
            this.ypos = ch / 2.0;
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
