package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

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
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private float startFOV; // 起始FOV
    private float endFOV; // 结束FOV
    private boolean useCustomFOV; // 是否使用自定义FOV

    /**
     * 构造螺旋路径
     * @param centerPosition 环绕中心点
     * @param radius 环绕半径
     * @param speed 角速度（度/秒）
     * @param height 垂直位移（总高度）
     * @param duration 运动持续时间（秒）
     */
    public SpiralPath(Vec3 centerPosition, double radius, double speed, double height, double duration) {
        this.centerPosition = centerPosition;
        this.radius = radius;
        // 角速度转换：度/秒 -> 弧度/tick
        this.angularSpeed = speed * Math.PI / 180.0 / 20.0; // 正确的转换公式
        this.verticalSpeed = height / (duration * 20.0); // 转换为单位/tick
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        
        // 计算旋转角度
        this.startRotation = calculateTargetRotation(centerPosition, centerPosition.add(radius, 0, 0));
        Vec3 endPosition = calculatePosition(durationInTicks);
        this.endRotation = calculateTargetRotation(centerPosition, endPosition);
        
        this.headingOffset = 0.0;
        this.startFOV = 70.0f;
        this.endFOV = 70.0f;
        this.useCustomFOV = false;
    }

    /**
     * 静态工厂方法：创建基于圈数的螺旋路径
     * 用户可以指定环绕的圈数，而不是直接指定角速度
     * @param centerPosition 环绕中心点
     * @param radius 环绕半径
     * @param numberOfTurns 环绕圈数
     * @param height 垂直位移（总高度）
     * @param duration 运动持续时间（秒）
     * @return SpiralPath 实例
     */
    public static SpiralPath createSpiralPathByTurns(Vec3 centerPosition, double radius, double numberOfTurns, double height, double duration) {
        // 计算角速度（度/秒），基于圈数和持续时间
        double speed = (numberOfTurns * 360.0) / duration;
        return new SpiralPath(centerPosition, radius, speed, height, duration);
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double angle = angularSpeed * timeInTicks; // 角速度 * 时间
        
        double x = centerPosition.x + radius * Math.cos(angle);
        double y = centerPosition.y + verticalSpeed * timeInTicks; // 独立的垂直运动
        double z = centerPosition.z + radius * Math.sin(angle);
        
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        // 计算路径切线方向
        Vec3 tangent = getTangent(timeInTicks);
        
        // 计算基础偏航角（路径切线方向）
        double baseYaw = Math.atan2(tangent.z, tangent.x) - Math.PI / 2;
        
        // 计算最终偏航角（基础偏航角 + 朝向偏移）
        double finalYaw = baseYaw + headingOffset;
        
        // 计算朝向中心点的俯仰角
        Vec3 currentPosition = getPosition(timeInTicks);
        double deltaY = centerPosition.y - currentPosition.y;
        double horizontalDistance = Math.sqrt(
            Math.pow(centerPosition.x - currentPosition.x, 2) + 
            Math.pow(centerPosition.z - currentPosition.z, 2)
        );
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        return new Vec3(pitch, finalYaw, 0);
    }
    
    @Override
    public Vec3 getTangent(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double angle = angularSpeed * timeInTicks;
        
        // 螺旋运动的切线方向
        double tangentX = -Math.sin(angle);
        double tangentZ = Math.cos(angle);
        return new Vec3(tangentX, 0, tangentZ).normalize();
    }
    
    @Override
    public double getFOV(double timeInTicks) {
        if (!useCustomFOV) {
            return 70.0;
        }
        
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        return (double) Mth.lerp((float) normalizedTime, startFOV, endFOV);
    }
    
    @Override
    public void setHeadingOffset(double headingOffset) {
        this.headingOffset = headingOffset;
    }
    
    @Override
    public void setFOVRange(float startFOV, float endFOV) {
        this.startFOV = startFOV;
        this.endFOV = endFOV;
        this.useCustomFOV = true;
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
        double angle = angularSpeed * timeInTicks;
        
        double x = centerPosition.x + radius * Math.cos(angle);
        double y = centerPosition.y + verticalSpeed * timeInTicks;
        double z = centerPosition.z + radius * Math.sin(angle);
        
        return new Vec3(x, y, z);
    }
}