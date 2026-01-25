package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * 贝塞尔曲线路径实现
 * 支持二次和三次贝塞尔曲线，实现丝滑的 S 型走位
 * 数学原理：B(t) = (1-t)^2 P0 + 2(1-t)t P1 + t^2 P2
 */
public class BezierPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 controlPosition;
    private final Vec3 endPosition;
    private final Vec3 startRotation;
    private final Vec3 endRotation;
    private final double durationInTicks; // 运动持续时间（ticks）
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private float startFOV; // 起始FOV
    private float endFOV; // 结束FOV
    private boolean useCustomFOV; // 是否使用自定义FOV

    /**
     * 构造二次贝塞尔曲线路径
     * @param startPosition 起点
     * @param controlPosition 控制点（决定曲线弧度）
     * @param endPosition 终点
     * @param startRotation 起始旋转角度
     * @param endRotation 结束旋转角度
     * @param duration 运动持续时间（秒）
     */
    public BezierPath(Vec3 startPosition, Vec3 controlPosition, Vec3 endPosition,
                     Vec3 startRotation, Vec3 endRotation, double duration) {
        this.startPosition = startPosition;
        this.controlPosition = controlPosition;
        this.endPosition = endPosition;
        this.startRotation = startRotation;
        this.endRotation = endRotation;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        this.headingOffset = 0.0;
        this.startFOV = 70.0f;
        this.endFOV = 70.0f;
        this.useCustomFOV = false;
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        return quadraticBezier(startPosition, controlPosition, endPosition, normalizedTime);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        // 计算路径切线方向
        Vec3 tangent = getTangent(timeInTicks);
        
        // 计算基础偏航角（路径切线方向）
        double baseYaw = Math.atan2(tangent.z, tangent.x) - Math.PI / 2;
        
        // 计算最终偏航角（基础偏航角 + 朝向偏移）
        double finalYaw = baseYaw + headingOffset;
        
        // 计算俯仰角（保持原有的平滑插值逻辑）
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double pitch = interpolateAngle(startRotation.x, endRotation.x, normalizedTime);

        return new Vec3(pitch, finalYaw, 0);
    }
    
    @Override
    public Vec3 getTangent(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        return quadraticBezierDerivative(startPosition, controlPosition, endPosition, normalizedTime).normalize();
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
     * 二次贝塞尔曲线导数（用于计算切线方向）
     */
    private Vec3 quadraticBezierDerivative(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
        double u = 1 - t;
        double dx = 2 * u * (p1.x - p0.x) + 2 * t * (p2.x - p1.x);
        double dy = 2 * u * (p1.y - p0.y) + 2 * t * (p2.y - p1.y);
        double dz = 2 * u * (p1.z - p0.z) + 2 * t * (p2.z - p1.z);
        return new Vec3(dx, dy, dz);
    }

    /**
     * 二次贝塞尔曲线插值
     */
    private Vec3 quadraticBezier(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
        double u = 1 - t;
        double u2 = u * u;
        double t2 = t * t;

        double x = u2 * p0.x + 2 * u * t * p1.x + t2 * p2.x;
        double y = u2 * p0.y + 2 * u * t * p1.y + t2 * p2.y;
        double z = u2 * p0.z + 2 * u * t * p1.z + t2 * p2.z;

        return new Vec3(x, y, z);
    }

    /**
     * 角度插值函数（支持最短路径插值）
     */
    private double interpolateAngle(double start, double end, double t) {
        double delta = end - start;
        
        while (delta > Math.PI) {
            delta -= 2 * Math.PI;
        }
        while (delta < -Math.PI) {
            delta += 2 * Math.PI;
        }
        
        double interpolated = start + delta * t;
        return normalizeAngle(interpolated);
    }

    /**
     * 归一化角度到 [0, 2π) 范围
     */
    private double normalizeAngle(double angle) {
        angle = angle % (2 * Math.PI);
        if (angle < 0) {
            angle += 2 * Math.PI;
        }
        return angle;
    }
}