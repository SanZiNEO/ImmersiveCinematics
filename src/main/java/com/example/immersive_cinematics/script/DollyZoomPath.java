package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 滑动变焦路径实现
 * 实现著名的"希区柯克变焦"效果，相机移动同时反向调节 FOV，产生空间压缩/拉伸错觉
 */
public class DollyZoomPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 endPosition;
    private final Vec3 targetPoint; // 聚焦点
    private final Vec3 startRotation;
    private final Vec3 endRotation;
    private final double startFOV;
    private final double endFOV;
    private final double durationInTicks; // 运动持续时间（ticks）

    /**
     * 构造滑动变焦路径
     * @param startPosition 起点
     * @param endPosition 终点
     * @param targetPoint 聚焦点（通常是路径起点和终点连线的中点）
     * @param startRotation 起始旋转角度
     * @param endRotation 结束旋转角度
     * @param duration 运动持续时间（秒）
     */
    public DollyZoomPath(Vec3 startPosition, Vec3 endPosition, Vec3 targetPoint,
                        Vec3 startRotation, Vec3 endRotation, double duration) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.targetPoint = targetPoint;
        this.startRotation = startRotation;
        this.endRotation = endRotation;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        
        // 计算起始和结束时的 FOV
        this.startFOV = calculateFOV(startPosition, targetPoint);
        this.endFOV = calculateFOV(endPosition, targetPoint);
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double t = easeInOutQuad(normalizedTime);
        
        double x = startPosition.x + (endPosition.x - startPosition.x) * t;
        double y = startPosition.y + (endPosition.y - startPosition.y) * t;
        double z = startPosition.z + (endPosition.z - startPosition.z) * t;
        
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        Vec3 currentPosition = getPosition(timeInTicks);
        return calculateTargetRotation(targetPoint, currentPosition);
    }

    @Override
    public float getFOV(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double t = easeInOutQuad(normalizedTime);
        
        // 线性插值 FOV
        double fov = startFOV + (endFOV - startFOV) * t;
        return (float) Math.max(Math.min(fov, 120.0), 5.0); // 限制在 5-120 度范围内
    }

    /**
     * 计算根据距离动态调整的 FOV
     */
    private double calculateFOV(Vec3 cameraPosition, Vec3 targetPosition) {
        double distance = cameraPosition.distanceTo(targetPosition);
        double h = 0.1; // 虚拟屏幕高度，控制变焦效果强度
        
        // 使用 FOV = 2 * arctan(h / (2 * d)) 公式
        double fovRadians = 2 * Math.atan(h / (2 * distance));
        return Math.toDegrees(fovRadians);
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
     * 缓动函数（Easing Function）
     * easeInOutQuad: t²*(3-2*t)
     */
    private double easeInOutQuad(double t) {
        return t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }
}