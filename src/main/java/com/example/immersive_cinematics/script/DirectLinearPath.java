package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 直接朝向目标的直线运动路径
 * 相机立即锁定目标方向，整个运动过程中始终看向终点
 * 适合快速移动和精确定位
 */
public class DirectLinearPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 endPosition;
    private final double durationInTicks; // 运动持续时间（ticks）

    public DirectLinearPath(Vec3 startPosition, Vec3 endPosition, double duration) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double x = interpolate(startPosition.x, endPosition.x, normalizedTime);
        double y = interpolate(startPosition.y, endPosition.y, normalizedTime);
        double z = interpolate(startPosition.z, endPosition.z, normalizedTime);
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        Vec3 currentPosition = getPosition(timeInTicks);

        double deltaX = endPosition.x - currentPosition.x;
        double deltaY = endPosition.y - currentPosition.y;
        double deltaZ = endPosition.z - currentPosition.z;

        // 计算偏航角（Yaw）：水平方向的角度
        double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;

        // 计算俯仰角（Pitch）：垂直方向的角度
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        return new Vec3(pitch, yaw, 0);
    }

    private double interpolate(double start, double end, double t) {
        return start + (end - start) * t;
    }
}