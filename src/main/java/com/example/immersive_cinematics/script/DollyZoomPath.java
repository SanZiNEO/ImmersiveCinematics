package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

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
    private double startFOV; // 去掉final修饰符，允许修改
    private final double startDistance; // 起始距离
    private final double durationInTicks; // 运动持续时间（ticks）
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private boolean useCustomFOV; // 是否使用自定义FOV
    private float customStartFOV; // 自定义起始FOV
    private float customEndFOV; // 自定义结束FOV

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
        
        // 计算起始距离和FOV
        this.startDistance = startPosition.distanceTo(targetPoint);
        this.startFOV = calculateFOV(startPosition, targetPoint);
        this.headingOffset = 0.0;
        this.useCustomFOV = false;
        this.customStartFOV = 70.0f;
        this.customEndFOV = 70.0f;
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
        // 计算路径切线方向
        Vec3 tangent = getTangent(timeInTicks);
        
        // 计算基础偏航角（路径切线方向）
        double baseYaw = Math.atan2(tangent.z, tangent.x) - Math.PI / 2;
        
        // 计算最终偏航角（基础偏航角 + 朝向偏移）
        double finalYaw = baseYaw + headingOffset;
        
        // 计算朝向目标点的俯仰角
        Vec3 currentPosition = getPosition(timeInTicks);
        double deltaY = targetPoint.y - currentPosition.y;
        double horizontalDistance = Math.sqrt(
            Math.pow(targetPoint.x - currentPosition.x, 2) + 
            Math.pow(targetPoint.z - currentPosition.z, 2)
        );
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        return new Vec3(pitch, finalYaw, 0);
    }
    
    @Override
    public Vec3 getTangent(double timeInTicks) {
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        
        // 计算路径切线方向（终点 - 起点的方向）
        Vec3 direction = endPosition.subtract(startPosition);
        return direction.normalize();
    }

    @Override
    public double getFOV(double timeInTicks) {
        if (useCustomFOV) {
            double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
            double t = easeInOutQuad(normalizedTime);
            return Mth.lerp((float) t, customStartFOV, customEndFOV);
        }
        
        // 根据当前位置到目标点的距离计算FOV，实现精确的希区柯克变焦效果
        Vec3 currentPosition = getPosition(timeInTicks);
        double currentDistance = currentPosition.distanceTo(targetPoint);
        
        // 使用公式: FOV(t) = 2 * arctan(tan(FOV_start/2) * d_start / d(t))
        double startFOVRadians = Math.toRadians(startFOV);
        double tanHalfStartFOV = Math.tan(startFOVRadians / 2);
        double tanHalfCurrentFOV = tanHalfStartFOV * startDistance / currentDistance;
        double currentFOVRadians = 2 * Math.atan(tanHalfCurrentFOV);
        
        return Math.max(Math.min(Math.toDegrees(currentFOVRadians), 120.0), 5.0); // 限制在 5-120 度范围内
    }
    
    @Override
    public void setHeadingOffset(double headingOffset) {
        this.headingOffset = headingOffset;
    }
    
    @Override
    public void setFOVRange(float startFOV, float endFOV) {
        this.customStartFOV = startFOV;
        this.customEndFOV = endFOV;
        this.useCustomFOV = true;
    }

    /**
     * 计算根据距离动态调整的 FOV
     * 优化虚拟屏幕高度，使其在合理距离范围内产生更自然的效果
     */
    private double calculateFOV(Vec3 cameraPosition, Vec3 targetPosition) {
        double distance = cameraPosition.distanceTo(targetPosition);
        
        // 优化虚拟屏幕高度，根据距离自动调整，确保FOV在合理范围内
        // 在10格距离时，FOV约为60度；在100格距离时，FOV约为10度
        double h = 0.5; // 增加虚拟屏幕高度，减少在短距离内的FOV变化
        
        // 使用 FOV = 2 * arctan(h / (2 * d)) 公式
        double fovRadians = 2 * Math.atan(h / (2 * distance));
        return Math.toDegrees(fovRadians);
    }

    /**
     * 静态工厂方法：创建简化版滑动变焦路径
     * 用户只需指定起始位置、聚焦目标位置、运动距离和持续时间
     */
    public static DollyZoomPath createSimpleDollyZoomPath(Vec3 startPosition, Vec3 targetPoint, double distance, double duration) {
        // 计算从起始位置到目标点的方向向量
        Vec3 direction = targetPoint.subtract(startPosition).normalize();
        
        // 计算结束位置（从起始位置沿反方向移动指定距离）
        Vec3 endPosition = startPosition.subtract(direction.scale(distance));
        
        // 计算起始和结束旋转角度（始终朝向目标点）
        Vec3 startRotation = calculateTargetRotation(targetPoint, startPosition);
        Vec3 endRotation = calculateTargetRotation(targetPoint, endPosition);
        
        return new DollyZoomPath(startPosition, endPosition, targetPoint, startRotation, endRotation, duration);
    }

    /**
     * 静态工厂方法：创建增强版滑动变焦路径，支持控制变焦强度
     * 用户可以指定起始位置、聚焦目标位置、运动距离、持续时间和变焦强度
     */
    public static DollyZoomPath createDollyZoomPathWithStrength(Vec3 startPosition, Vec3 targetPoint, double distance, double duration, double strength) {
        DollyZoomPath path = createSimpleDollyZoomPath(startPosition, targetPoint, distance, duration);
        
        // 根据变焦强度调整起始FOV，强度范围0.1-2.0，默认1.0
        double adjustedStrength = Math.max(0.1, Math.min(2.0, strength));
        double baseFOV = path.calculateFOV(startPosition, targetPoint);
        path.startFOV = baseFOV * adjustedStrength;
        
        return path;
    }
    /**
     * 计算目标位置的旋转角度
     */
    private static Vec3 calculateTargetRotation(Vec3 target, Vec3 cameraPosition) {
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