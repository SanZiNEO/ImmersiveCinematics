package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

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
    private final double durationInTicks; // 运动持续时间（ticks）
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private float startFOV; // 起始FOV
    private float endFOV; // 结束FOV
    private boolean useCustomFOV; // 是否使用自定义FOV

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
        this.angularVelocity = speed * Math.PI / 180.0; // 转换为弧度/tick（输入为弧度/秒）
        this.height = height;
        this.startAngle = 0;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        this.headingOffset = 0.0;
        this.startFOV = 70.0f;
        this.endFOV = 70.0f;
        this.useCustomFOV = false;
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
        // 计算路径切线方向
        Vec3 tangent = getTangent(timeInTicks);
        
        // 计算基础偏航角（路径切线方向）
        double baseYaw = Math.atan2(tangent.z, tangent.x) - Math.PI / 2;
        
        // 计算最终偏航角（基础偏航角 + 朝向偏移）
        double finalYaw = baseYaw + headingOffset;
        
        // 计算朝向中心点的俯仰角
        double targetX = centerPosition.x;
        double targetY = centerPosition.y;
        double targetZ = centerPosition.z;
        
        Vec3 currentPosition = getPosition(timeInTicks);
        
        double deltaY = targetY - currentPosition.y;
        double horizontalDistance = Math.sqrt(
            Math.pow(targetX - currentPosition.x, 2) + 
            Math.pow(targetZ - currentPosition.z, 2)
        );
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        return new Vec3(pitch, finalYaw, 0);
    }
    
    @Override
    public Vec3 getTangent(double timeInTicks) {
        // 计算当前时间的角度（直接使用传入的时间，支持小数）
        double angle = startAngle + (angularVelocity * timeInTicks);
        
        // 圆周运动的切线方向
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
}