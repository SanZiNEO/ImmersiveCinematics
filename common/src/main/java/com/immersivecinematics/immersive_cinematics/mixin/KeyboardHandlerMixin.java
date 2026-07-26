package com.immersivecinematics.immersive_cinematics.mixin;

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
 * 键盘输入处理 — 接收层 + 传输层分离。
 * <p>
 * 接收层：{@code @Inject HEAD} 捕获原始事件，永不 cancel。
 * 传输层：通过 {@link InputRouter} 决定事件目标，在接收层之后立即执行。
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    /** 输入路由器，可替换实现 */
    @Unique
    private static InputRouter inputRouter = InputRouter.createDefault();

    @Unique
    private static void handleKeyPress(int key, int scanCode, int action, int modifiers) {
        InputTarget target = inputRouter.routeKeyboard(key, scanCode, action, modifiers);
        if (target == InputTarget.GAME) {
            return; // 不 cancel，原样交给游戏
        }
        if (target == InputTarget.SELF) {
            boolean pressed = action == 1 || action == 2; // GLFW_PRESS=1, GLFW_REPEAT=2
            CinematicKeyBindings.SKIP_KEY.setDown(pressed);
            // 不 cancel 也不继续阻断——SELF 模式意味事件被我们消费了
            // 但这里需要 cancel 才能阻止传到游戏
        }
        // 对于 BLOCK 和 SELF（见上），都需要 ci.cancel()
        // 但 @Inject 方法无法直接 cancel 原始方法
        // 需要借助 cancellable CI
    }

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long windowPointer, int key, int scanCode,
                            int action, int modifiers, CallbackInfo ci) {
        // === 接收层：始终接收，不 cancel ===
        if (windowPointer == 0) return; // 忽略无效窗口

        // === 传输层：路由决策 ===
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
        }
    }

    /** 替换输入路由器实例（供测试或扩展用） */
    @Unique
    private static void setInputRouter(InputRouter router) {
        inputRouter = router;
    }
}
