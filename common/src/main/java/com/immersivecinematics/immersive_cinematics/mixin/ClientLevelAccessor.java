package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ClientLevel.levelRenderer} 的读取口：目标维度的 {@code ClientLevel} 与它自己的渲染器绑定，
 * 服务端元数据包接入后需要在两侧交叉校验（后续切片用；当前只读，不改写 final 字段）。
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {

    /** 该客户端世界绑定的渲染器（构造时传入，恒非 {@code null}）。 */
    @Accessor("levelRenderer")
    LevelRenderer ic$getLevelRenderer();
}
