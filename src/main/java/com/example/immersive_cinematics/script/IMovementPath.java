package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 运动路径接口，定义了相机运动的基准
 * 所有运动方式必须实现此接口，提供特定时间点的位置、旋转角度和 FOV
 * 支持小数级别的时间计算，实现帧级精度的平滑插值
 * 
 * 遵循策略模式设计，路径类应保持无状态、纯粹的数学计算
 */
public interface IMovementPath {

    /**
     * 获取指定时间的相机位置（支持小数时间）
     * @param timeInTicks 时间（以 ticks 为单位，支持小数，包含 partialTicks）
     * @return 相机位置向量
     */
    Vec3 getPosition(double timeInTicks);

    /**
     * 获取指定时间的相机旋转角度（支持小数时间）
     * @param timeInTicks 时间（以 ticks 为单位，支持小数，包含 partialTicks）
     * @return 相机旋转向量，包含俯仰角(φ)和偏航角(θ)，单位为弧度
     */
    Vec3 getRotation(double timeInTicks);

    /**
     * 获取指定时间的相机 FOV（视场角）
     * 默认为 70 度，可在需要时重写以实现动态 FOV 效果（如滑动变焦）
     * @param timeInTicks 时间（以 ticks 为单位，支持小数，包含 partialTicks）
     * @return FOV 值，单位为度
     */
    default float getFOV(double timeInTicks) {
        return 70.0f;
    }
}
