package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

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
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        return quadraticBezier(startPosition, controlPosition, endPosition, normalizedTime);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double pitch = interpolateAngle(startRotation.x, endRotation.x, normalizedTime);
        double yaw = interpolateAngle(startRotation.y, endRotation.y, normalizedTime);
        return new Vec3(pitch, yaw, 0);
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