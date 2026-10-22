package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.control.FlightModeManager;
import com.immersivecinematics.immersive_cinematics.control.InputRouter;
import com.immersivecinematics.immersive_cinematics.control.InputTarget;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘输入处理 —— 接收层 + 传输层分离。
 *
 * 接收层在 {@code keyPress} 的 HEAD 捕获原始事件、永不提前 cancel；传输层 {@link InputRouter} 决定目标，
 * 接收层再按目标 cancel。跳过键（{@link CinematicKeyBindings#SKIP_KEY}）由 SELF 分支处理：它先于
 * {@code blockKeyboard} 判定，用户关掉键盘屏蔽也能长按跳过；代价是这次按下被 cancel 后原版不再更新
 * {@link KeyMapping}，所以必须在这里手动 {@code setDown}。
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    /** 输入路由器，可替换实现 */
    @Unique
    private static InputRouter inputRouter = InputRouter.createDefault();

    /** 未接线：本方法没有 cancel 通道，实际路由在 {@link #onKeyPress}。 */
    @Unique
    private static void handleKeyPress(int key, int scanCode, int action, int modifiers) {
        InputTarget target = inputRouter.routeKeyboard(key, scanCode, action, modifiers);
        if (target == InputTarget.GAME) {
            return; // 不 cancel，原样交给游戏
        }
        if (target == InputTarget.SELF) {
            boolean pressed = action == 1 || action == 2; // GLFW_PRESS=1, GLFW_REPEAT=2
            CinematicKeyBindings.SKIP_KEY.setDown(pressed);
        }
    }

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long windowPointer, int key, int scanCode,
                            int action, int modifiers, CallbackInfo ci) {
        // 接收层：任何键都先路由，只有路由结果不是 GAME 才 cancel
        if (windowPointer == 0) return; // 忽略无效窗口

        // 传输层：路由决策
        InputTarget target = inputRouter.routeKeyboard(key, scanCode, action, modifiers);

        switch (target) {
            case GAME:
                return; // 放行
            case SELF:
                // 跳过键：只更新 KeyMapping，不交给游戏
                boolean pressed = action == 1 || action == 2;
                CinematicKeyBindings.SKIP_KEY.setDown(pressed);
                ci.cancel();
                return;
            case BLOCK:
                ci.cancel();
                return;
            case FLIGHT:
                // 走中转层：统一喂给独立飞控模块，再拦截，不给游戏/编辑器 UI
                FlightModeManager.INSTANCE.onKeyEvent(key, scanCode, action);
                ci.cancel();
                return;
        }
    }

    /** 替换输入路由器实例（供测试或扩展用） */
    @Unique
    private static void setInputRouter(InputRouter router) {
        inputRouter = router;
    }
}
