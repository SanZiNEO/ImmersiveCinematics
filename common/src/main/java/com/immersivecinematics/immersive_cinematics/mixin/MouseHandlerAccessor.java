package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MouseHandler 字段访问接口（清空鼠标视角累积量用）。
 * <p>
 * 注意：业务代码**不能直接引用 mixin 类**（Fabric/Sponge 会抛
 * {@code IllegalClassLoadError: Mixin ... cannot be referenced directly}），
 * 注入目标类并从外部调用的标准做法就是本类这种 @Mixin + @Accessor 接口——
 * 接口自身不是 mixin 类，业务代码引用它完全合法。
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    /** 覆盖 accumulatedDX（播放退出时清零，避免带出播放期间积压的鼠标位移） */
    @Accessor("accumulatedDX")
    void setAccumulatedDX(double value);

    /** 覆盖 accumulatedDY */
    @Accessor("accumulatedDY")
    void setAccumulatedDY(double value);
}
