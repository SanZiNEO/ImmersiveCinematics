package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 平滑曲线运动路径
 * 从玩家初始视角开始，平滑过渡到目标方向的视角
 * 适合电影级的平滑运镜
 */
public class SmoothLinearPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 endPosition;
    private final Vec3 startRotation;
    private final Vec3 endRotation;
    private final double durationInTicks; // 运动持续时间（ticks）

    public SmoothLinearPath(Vec3 startPosition, Vec3 endPosition, Vec3 startRotation, Vec3 endRotation, double duration) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.startRotation = startRotation;
        this.endRotation = endRotation;
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
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double pitch = interpolateAngle(startRotation.x, endRotation.x, normalizedTime);
        double yaw = interpolateAngle(startRotation.y, endRotation.y, normalizedTime);
        return new Vec3(pitch, yaw, 0);
    }

    /**
     * 角度插值函数（支持最短路径插值）
     * @param start 起始角度（弧度）
     * @param end 结束角度（弧度）
     * @param t 时间进度 [0, 1]
     * @return 插值结果（弧度）
     */
    private double interpolateAngle(double start, double end, double t) {
        double delta = end - start;
        
        // 确保角度差在 [-π, π] 范围内，这样会选择最短路径
        while (delta > Math.PI) {
            delta -= 2 * Math.PI;
        }
        while (delta < -Math.PI) {
            delta += 2 * Math.PI;
        }
        
        double interpolated = start + delta * t;
        
        // 确保结果在 [0, 2π) 范围内
        interpolated = normalizeAngle(interpolated);
        
        return interpolated;
    }

    /**
     * 归一化角度到 [0, 2π) 范围
     * @param angle 角度（弧度）
     * @return 归一化后的角度
     */
    private double normalizeAngle(double angle) {
        angle = angle % (2 * Math.PI);
        if (angle < 0) {
            angle += 2 * Math.PI;
        }
        return angle;
    }

    private double interpolate(double start, double end, double t) {
        return start + (end - start) * t;
    }
}