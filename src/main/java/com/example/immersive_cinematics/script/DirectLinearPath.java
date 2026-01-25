package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * 直接朝向目标的直线运动路径
 * 相机立即锁定目标方向，整个运动过程中始终看向终点
 * 适合快速移动和精确定位
 */
public class DirectLinearPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 endPosition;
    private final double durationInTicks; // 运动持续时间（ticks）
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private float startFOV; // 起始FOV
    private float endFOV; // 结束FOV
    private boolean useCustomFOV; // 是否使用自定义FOV

    public DirectLinearPath(Vec3 startPosition, Vec3 endPosition, double duration) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        this.headingOffset = 0.0;
        this.startFOV = 70.0f;
        this.endFOV = 70.0f;
        this.useCustomFOV = false;
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
        // 计算路径切线方向
        Vec3 tangent = getTangent(timeInTicks);
        
        // 计算基础偏航角（路径切线方向）
        double baseYaw = Math.atan2(tangent.z, tangent.x) - Math.PI / 2;
        
        // 计算最终偏航角（基础偏航角 + 朝向偏移）
        double finalYaw = baseYaw + headingOffset;
        
        // 计算俯仰角
        double deltaY = endPosition.y - getPosition(timeInTicks).y;
        double horizontalDistance = Math.sqrt(
            Math.pow(endPosition.x - getPosition(timeInTicks).x, 2) + 
            Math.pow(endPosition.z - getPosition(timeInTicks).z, 2)
        );
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        return new Vec3(pitch, finalYaw, 0);
    }

    @Override
    public Vec3 getTangent(double timeInTicks) {
        // 对于直线路径，切线方向始终是终点减去起点的方向
        Vec3 direction = endPosition.subtract(startPosition);
        return direction.normalize();
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

    private double interpolate(double start, double end, double t) {
        return start + (end - start) * t;
    }
}
