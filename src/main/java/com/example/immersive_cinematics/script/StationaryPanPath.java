package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * 静态旋转/摇镜头路径实现
 * 相机位置固定不动，但镜头的朝向（Yaw/Pitch）根据时间发生变化
 * 模拟固定三脚架的拍摄效果
 */
public class StationaryPanPath implements IMovementPath {

    private final Vec3 fixedPosition; // 固定位置
    private final Vec3 startRotation; // 起始旋转角度
    private final Vec3 endRotation; // 结束旋转角度
    private final double durationInTicks; // 运动持续时间（ticks）
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private float startFOV; // 起始FOV
    private float endFOV; // 结束FOV
    private boolean useCustomFOV; // 是否使用自定义FOV

    /**
     * 构造静态旋转路径
     * @param fixedPosition 相机固定位置
     * @param startRotation 起始旋转角度
     * @param endRotation 结束旋转角度
     * @param duration 运动持续时间（秒）
     */
    public StationaryPanPath(Vec3 fixedPosition, Vec3 startRotation, Vec3 endRotation, double duration) {
        this.fixedPosition = fixedPosition;
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
        return fixedPosition; // 位置固定不变
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double t = easeInOutQuad(normalizedTime);
        
        double pitch = interpolateAngle(startRotation.x, endRotation.x, t);
        double yaw = interpolateAngle(startRotation.y, endRotation.y, t);
        
        // 应用朝向偏移
        double finalYaw = yaw + headingOffset;
        
        return new Vec3(pitch, finalYaw, 0);
    }
    
    @Override
    public Vec3 getTangent(double timeInTicks) {
        // 对于静态路径，我们可以返回一个默认的切线方向
        // 或者根据当前旋转角度计算前向方向
        Vec3 currentRotation = getRotation(timeInTicks);
        double pitch = currentRotation.x;
        double yaw = currentRotation.y;
        
        double forwardX = -Math.sin(yaw) * Math.cos(pitch);
        double forwardZ = Math.cos(yaw) * Math.cos(pitch);
        double forwardY = -Math.sin(pitch);
        
        return new Vec3(forwardX, forwardY, forwardZ).normalize();
    }
    
    @Override
    public double getFOV(double timeInTicks) {
        if (!useCustomFOV) {
            return 70.0;
        }
        
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double t = easeInOutQuad(normalizedTime);
        return (double) Mth.lerp((float) t, startFOV, endFOV);
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

    /**
     * 缓动函数（Easing Function）
     * easeInOutQuad: t²*(3-2*t)
     */
    private double easeInOutQuad(double t) {
        return t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }
}