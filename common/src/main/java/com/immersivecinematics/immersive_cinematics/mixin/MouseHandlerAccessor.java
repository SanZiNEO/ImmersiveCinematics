package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 直写原版 {@link MouseHandler} 的鼠标视角累积量。
 *
 * <p>背景：vanilla {@code onMove} 先 {@code accumulatedDX/DY += Δ}，随后 {@code turnPlayer()} 消费并清零
 * ——“消费即清零”是原版不变量。本模组在屏蔽期于 {@code turnPlayer} HEAD {@code ci.cancel()}，该清零被整段
 * 跳过，于是播放期间累积量只增不减，退出后第一次 {@code turnPlayer} 会按积压位移转动视角（首帧跳变）。
 * 退出过场时用本 Accessor 把两个字段直接写回 0，恢复该不变量。</p>
 *
 * <p>与 {@link MinecraftAccessor} 同构：纯 Accessor Mixin，不使用反射（本模组 mixin 插件约定）。</p>
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Accessor("accumulatedDX")
    void ic$setAccumulatedDX(double value);

    @Accessor("accumulatedDY")
    void ic$setAccumulatedDY(double value);
}
