package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 环绕运动路径实现
 * 基于极坐标转换计算相机围绕目标点的圆周运动
 * 支持帧级精度的平滑插值，使用 timeInTicks（包含 partialTicks 的小数时间）
 */
public class OrbitPath implements IMovementPath {

    private final Vec3 centerPosition; // 环绕中心点
    private final double radius; // 环绕半径
    private final double angularVelocity; // 角速度（弧度/tick）
    private final double height; // 相机高度
    private final double startAngle; // 初始角度

    /**
     * 构造函数，创建环绕运动路径
     * @param centerPosition 环绕中心点
     * @param radius 环绕半径
     * @param speed 旋转速度（弧度/秒）
     * @param height 相机高度
     * @param duration 运动持续时间（秒）
     */
    public OrbitPath(Vec3 centerPosition, double radius, double speed, double height, double duration) {
        this.centerPosition = centerPosition;
        this.radius = radius;
        this.angularVelocity = speed / 20.0; // 转换为弧度/tick（20 tick/s）
        this.height = height;
        this.startAngle = 0;
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        // 计算当前时间的角度（直接使用传入的时间，支持小数）
        double angle = startAngle + (angularVelocity * timeInTicks);

        // 极坐标转换为笛卡尔坐标
        double x = centerPosition.x + radius * Math.cos(angle);
        double y = centerPosition.y + height;
        double z = centerPosition.z + radius * Math.sin(angle);
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        // 计算朝向中心点的旋转角度
        double targetX = centerPosition.x;
        double targetY = centerPosition.y;
        double targetZ = centerPosition.z;

        Vec3 currentPosition = getPosition(timeInTicks);

        // 计算偏航角（Yaw） - 水平旋转
        double deltaX = targetX - currentPosition.x;
        double deltaZ = targetZ - currentPosition.z;
        double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;

        // 计算俯仰角（Pitch） - 垂直旋转
        double deltaY = targetY - currentPosition.y;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        return new Vec3(pitch, yaw, 0);
    }
}
