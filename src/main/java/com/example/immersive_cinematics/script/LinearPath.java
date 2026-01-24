package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 直线运动路径实现
 * 使用线性插值计算相机在两点之间的位置和旋转角度
 * 支持帧级精度的平滑插值，使用 timeInTicks（包含 partialTicks 的小数时间）
 */
public class LinearPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 endPosition;
    private final Vec3 startRotation;
    private final Vec3 endRotation;
    private final double durationInTicks; // 运动持续时间（ticks）

    /**
     * 构造函数，创建从起始点到终点的直线运动路径
     * @param startPosition 起始位置
     * @param endPosition 终点位置
     * @param startRotation 起始旋转角度(俯仰角和偏航角)，单位为弧度
     * @param endRotation 终点旋转角度(俯仰角和偏航角)，单位为弧度
     * @param duration 运动持续时间（秒）
     */
    public LinearPath(Vec3 startPosition, Vec3 endPosition, Vec3 startRotation, Vec3 endRotation, double duration) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.startRotation = startRotation;
        this.endRotation = endRotation;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        // 将时间转换为归一化进度 [0, 1]
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        // 线性插值计算位置
        double x = interpolate(startPosition.x, endPosition.x, normalizedTime);
        double y = interpolate(startPosition.y, endPosition.y, normalizedTime);
        double z = interpolate(startPosition.z, endPosition.z, normalizedTime);
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        // 将时间转换为归一化进度 [0, 1]
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        // 线性插值计算旋转角度
        double pitch = interpolate(startRotation.x, endRotation.x, normalizedTime);
        double yaw = interpolate(startRotation.y, endRotation.y, normalizedTime);
        return new Vec3(pitch, yaw, 0);
    }

    /**
     * 线性插值函数
     * @param start 起始值
     * @param end 结束值
     * @param t 时间进度 [0, 1]
     * @return 插值结果
     */
    private double interpolate(double start, double end, double t) {
        return start + (end - start) * t;
    }
}
