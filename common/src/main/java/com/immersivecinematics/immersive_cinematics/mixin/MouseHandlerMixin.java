package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.control.FlightController;
import com.immersivecinematics.immersive_cinematics.control.InputRouter;
import com.immersivecinematics.immersive_cinematics.control.InputTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
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

    /** 飞行取景：我们自己记录上一次鼠标位置，避免读 vanilla 私有字段 */
    @Unique
    private double icLastMouseX;

    @Unique
    private double icLastMouseY;

    @Unique
    private boolean icHasLastMouse;

    @Unique
    private boolean icAwaitingCenterSync;

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
        if (!FlightController.INSTANCE.isActive()) {
            // 非飞行：正常交给 vanilla；顺带清掉我们自己的残留同步状态
            icAwaitingCenterSync = false;
            icHasLastMouse = false;
            return;
        }

        // 等待“回中后”的那一次事件：让 vanilla 用 setIgnoreFirstMove 同步内部位置
        if (icAwaitingCenterSync) {
            icAwaitingCenterSync = false;
            icLastMouseX = x;
            icLastMouseY = y;
            // 不 cancel，让 vanilla 正常处理这次事件（此时 xpos==d，不会产生累积）
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (!icHasLastMouse) {
            // 用公开 getter 初始化上一次位置，避免读私有字段
            icLastMouseX = mc.mouseHandler.xpos();
            icLastMouseY = mc.mouseHandler.ypos();
            icHasLastMouse = true;
        }

        double dx = x - icLastMouseX;
        double dy = y - icLastMouseY;
        FlightController.INSTANCE.onMouseMove(dx, dy);

        long win = mc.getWindow().getWindow();
        int cw = mc.getWindow().getScreenWidth();
        int ch = mc.getWindow().getScreenHeight();
        GLFW.glfwSetCursorPos(win, cw / 2.0, ch / 2.0);
        // 公开 API：让下一次 onMove 把 vanilla 内部位置同步到屏幕中心
        mc.mouseHandler.setIgnoreFirstMove();
        icLastMouseX = cw / 2.0;
        icLastMouseY = ch / 2.0;
        icAwaitingCenterSync = true;
        ci.cancel();
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
