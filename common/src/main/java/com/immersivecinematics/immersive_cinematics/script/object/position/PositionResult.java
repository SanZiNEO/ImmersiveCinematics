package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

/**
 * 位置求值结果：世界坐标 + 本帧基准坐标系（{@code null} = 本帧没有参考系，例如绝对位置 /
 * 世界轴偏移）。
 *
 * <p>{@code frame} 的 {@code origin} 是基准锚点（点源解析点；冻结时换成冻结后的世界点），
 * 三轴由方向源给出（单位向量，{@code up} 固定世界竖直）。</p>
 */
public record PositionResult(Vec3 world, PointFrame frame) {
}
