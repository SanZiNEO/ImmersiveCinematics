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
    default double getFOV(double timeInTicks) {
        return 70.0;
    }
    
    /**
     * 获取运动路径在指定时间的切线方向（用于计算朝向偏移）
     * @param timeInTicks 时间（以 ticks 为单位，支持小数，包含 partialTicks）
     * @return 切线方向向量
     */
    Vec3 getTangent(double timeInTicks);
    
    /**
     * 设置相机朝向偏移角度（用于实现倒走、侧拍等效果）
     * @param headingOffset 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
     */
    default void setHeadingOffset(double headingOffset) {
        // 默认实现，可在具体路径类中重写
    }
    
    /**
     * 设置FOV插值范围
     * @param startFOV 起始FOV
     * @param endFOV 结束FOV
     */
    default void setFOVRange(float startFOV, float endFOV) {
        // 默认实现，可在具体路径类中重写
    }
}
