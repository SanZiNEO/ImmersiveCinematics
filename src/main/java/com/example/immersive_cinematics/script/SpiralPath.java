package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 螺旋上升/下降路径实现
 * 在环绕运动的基础上添加垂直方向的线性偏移，实现史诗级登场效果
 */
public class SpiralPath implements IMovementPath {

    private final Vec3 centerPosition;
    private final double radius;
    private final double angularSpeed; // 角速度（弧度/tick）
    private final double verticalSpeed; // 垂直速度（单位/tick）
    private final Vec3 startRotation;
    private final Vec3 endRotation;
    private final double durationInTicks; // 运动持续时间（ticks）

    /**
     * 构造螺旋路径
     * @param centerPosition 环绕中心点
     * @param radius 环绕半径
     * @param speed 角速度（弧度/秒）
     * @param height 垂直位移（总高度）
     * @param duration 运动持续时间（秒）
     */
    public SpiralPath(Vec3 centerPosition, double radius, double speed, double height, double duration) {
        this.centerPosition = centerPosition;
        this.radius = radius;
        this.angularSpeed = speed * Math.PI / 180.0 * 20.0; // 转换为弧度/tick
        this.verticalSpeed = height / (duration * 20.0); // 转换为单位/tick
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        
        // 计算旋转角度
        this.startRotation = calculateTargetRotation(centerPosition, centerPosition.add(radius, 0, 0));
        Vec3 endPosition = calculatePosition(durationInTicks);
        this.endRotation = calculateTargetRotation(centerPosition, endPosition);
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double angle = angularSpeed * normalizedTime * durationInTicks;
        
        double x = centerPosition.x + radius * Math.cos(angle);
        double y = centerPosition.y + verticalSpeed * normalizedTime * durationInTicks;
        double z = centerPosition.z + radius * Math.sin(angle);
        
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        Vec3 currentPosition = getPosition(timeInTicks);
        return calculateTargetRotation(centerPosition, currentPosition);
    }

    /**
     * 计算目标位置的旋转角度
     */
    private Vec3 calculateTargetRotation(Vec3 target, Vec3 cameraPosition) {
        double deltaX = target.x - cameraPosition.x;
        double deltaZ = target.z - cameraPosition.z;
        double deltaY = target.y - cameraPosition.y;
        
        // 计算偏航角（Yaw）
        double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;
        
        // 计算俯仰角（Pitch）
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = -Math.atan2(deltaY, horizontalDistance);
        
        return new Vec3(pitch, yaw, 0);
    }

    /**
     * 计算特定时间的位置（辅助方法）
     */
    private Vec3 calculatePosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double angle = angularSpeed * normalizedTime * durationInTicks;
        
        double x = centerPosition.x + radius * Math.cos(angle);
        double y = centerPosition.y + verticalSpeed * normalizedTime * durationInTicks;
        double z = centerPosition.z + radius * Math.sin(angle);
        
        return new Vec3(x, y, z);
    }
}