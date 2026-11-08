package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

/**
 * 本帧基准坐标系（表达空间 {@link PositionSpace#BASE} 的参照系）：
 * 原点 + 三轴（单位向量，{@code fwd} / {@code right} / {@code up}）。
 *
 * <p>由位置通道建立，每帧一套；{@code null} = 本帧没有基准坐标系。</p>
 */
public record PointFrame(Vec3 origin, Vec3 fwd, Vec3 right, Vec3 up) {}
