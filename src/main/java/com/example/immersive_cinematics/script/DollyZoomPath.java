package com.example.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * 滑动变焦路径实现
 * 实现著名的"希区柯克变焦"效果，相机移动同时反向调节 FOV，产生空间压缩/拉伸错觉
 * 简化版接口：用户只需指定起始位置和聚焦点，系统自动计算相机移动距离和FOV变化
 */
public class DollyZoomPath implements IMovementPath {

    private final Vec3 startPosition;
    private final Vec3 endPosition;
    private final Vec3 targetPoint; // 聚焦点
    private final Vec3 startRotation;
    private final Vec3 endRotation;
    private final double durationInTicks; // 运动持续时间（ticks）
    private double headingOffset; // 朝向偏移角度（相对于路径切线方向的偏移，单位：弧度）
    private boolean useCustomFOV; // 是否使用自定义FOV
    private float customStartFOV; // 自定义起始FOV
    private float customEndFOV; // 自定义结束FOV
    private double visualConstantK; // 视觉比例常量 K = d_ref * tan(FOV_ref/2)
    private final double minDistance = 0.1; // 最小距离，防止FOV跳变
    private static final double STOP_DISTANCE = 1.0; // 停止距离：在聚焦点前多少格停下

    /**
     * 构造滑动变焦路径
     * @param startPosition 起点
     * @param endPosition 终点
     * @param targetPoint 聚焦点
     * @param startRotation 起始旋转角度
     * @param endRotation 结束旋转角度
     * @param duration 运动持续时间（秒）
     * @param referenceDistance 参考距离
     * @param referenceFOV 参考FOV
     */
    public DollyZoomPath(Vec3 startPosition, Vec3 endPosition, Vec3 targetPoint,
                        Vec3 startRotation, Vec3 endRotation, double duration,
                        double referenceDistance, double referenceFOV) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.targetPoint = targetPoint;
        this.startRotation = startRotation;
        this.endRotation = endRotation;
        this.durationInTicks = duration * 20.0; // 转换为 ticks（20 tick/s）
        
        // 计算视觉比例常量 K
        double referenceFOVRadians = Math.toRadians(referenceFOV);
        this.visualConstantK = referenceDistance * Math.tan(referenceFOVRadians / 2);
        
        this.headingOffset = 0.0;
        this.useCustomFOV = false;
        this.customStartFOV = 70.0f;
        this.customEndFOV = 70.0f;
    }
    
    /**
     * 构造滑动变焦路径（默认参考参数）
     * @param startPosition 起点
     * @param endPosition 终点
     * @param targetPoint 聚焦点
     * @param startRotation 起始旋转角度
     * @param endRotation 结束旋转角度
     * @param duration 运动持续时间（秒）
     */
    public DollyZoomPath(Vec3 startPosition, Vec3 endPosition, Vec3 targetPoint,
                        Vec3 startRotation, Vec3 endRotation, double duration) {
        this(startPosition, endPosition, targetPoint, startRotation, endRotation, duration, 
             startPosition.distanceTo(targetPoint), 70.0); // 默认参考距离为起始距离，参考FOV为70度
    }

    @Override
    public Vec3 getPosition(double timeInTicks) {
        // 使用线性时间进度，然后应用缓动函数
        double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
        double easedTime = easeInOutQuad(normalizedTime);
        
        double x = startPosition.x + (endPosition.x - startPosition.x) * easedTime;
        double y = startPosition.y + (endPosition.y - startPosition.y) * easedTime;
        double z = startPosition.z + (endPosition.z - startPosition.z) * easedTime;
        
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 getRotation(double timeInTicks) {
        // 摄像机始终朝向目标点，不管移动方向
        Vec3 currentPosition = getPosition(timeInTicks);
        
        // 计算朝向目标点的偏航角和俯仰角
        double deltaX = targetPoint.x - currentPosition.x;
        double deltaZ = targetPoint.z - currentPosition.z;
        double deltaY = targetPoint.y - currentPosition.y;
        
        // 计算偏航角（Yaw）
        double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;
        
        // 计算俯仰角（Pitch）
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = -Math.atan2(deltaY, horizontalDistance);
        
        // 应用朝向偏移
        double finalYaw = yaw + headingOffset;

        return new Vec3(pitch, finalYaw, 0);
    }
    
    @Override
    public Vec3 getTangent(double timeInTicks) {
        // 计算路径切线方向（终点 - 起点的方向）
        Vec3 direction = endPosition.subtract(startPosition);
        return direction.normalize();
    }

    @Override
    public double getFOV(double timeInTicks) {
        if (useCustomFOV) {
            double normalizedTime = Math.min(timeInTicks / durationInTicks, 1.0);
            double easedTime = easeInOutQuad(normalizedTime);
            return Mth.lerp((float) easedTime, customStartFOV, customEndFOV);
        }
        
        // FOV 必须直接根据“当前实际距离”反推！
        Vec3 currentPosition = getPosition(timeInTicks);
        double currentDistance = Math.max(currentPosition.distanceTo(targetPoint), minDistance);
        
        // 使用公式: FOV = 2 * arctan(K / d)
        double tanHalfCurrentFOV = visualConstantK / currentDistance;
        double currentFOVRadians = 2 * Math.atan(tanHalfCurrentFOV);
        
        // 边界保护：确保FOV在合理范围内
        double finalFOV = Math.toDegrees(currentFOVRadians);
        return Math.max(Math.min(finalFOV, 120.0), 5.0);
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
     * 静态工厂方法：创建简化版滑动变焦路径
     * 用户只需指定起始位置、聚焦目标位置和持续时间
     * 摄像机自动在聚焦点前1格停下，自动计算FOV变化
     * 
     * 重构：使用终点锚定法计算K值，确保FOV覆盖完整的移动路径
     */
    public static DollyZoomPath createSimpleDollyZoomPath(Vec3 startPosition, Vec3 targetPoint, double duration, boolean forward) {
        Vec3 actualStartPosition;
        Vec3 actualEndPosition;
        double referenceDistance;
        double referenceFOV;
        
        if (forward) {
            // 向前移动：从起始位置向目标点前1格移动，FOV增大
            Vec3 direction = targetPoint.subtract(startPosition).normalize();
            double totalDistance = startPosition.distanceTo(targetPoint) - STOP_DISTANCE;
            actualStartPosition = startPosition;
            actualEndPosition = startPosition.add(direction.scale(totalDistance));
            referenceDistance = STOP_DISTANCE; // 终点距离作为参考距离
            referenceFOV = 110.0; // 终点目标FOV
        } else {
            // 向后移动：从目标点前1格向起始位置移动，FOV减小，摄像机始终朝向目标点
            Vec3 direction = targetPoint.subtract(startPosition).normalize();
            double totalDistance = startPosition.distanceTo(targetPoint) - STOP_DISTANCE;
            actualStartPosition = startPosition.add(direction.scale(totalDistance)); // 目标点前1格位置
            actualEndPosition = startPosition; // 回到起始位置
            referenceDistance = actualStartPosition.distanceTo(targetPoint); // 起点距离作为参考距离（目标点前1格）
            referenceFOV = 110.0; // 起点目标FOV（较大，因为距离近）
        }
        
        // 计算起始和结束旋转角度（始终朝向目标点）
        Vec3 startRotation = calculateTargetRotation(targetPoint, actualStartPosition);
        Vec3 endRotation = calculateTargetRotation(targetPoint, actualEndPosition);
        
        return new DollyZoomPath(actualStartPosition, actualEndPosition, targetPoint, startRotation, endRotation, duration,
                               referenceDistance, referenceFOV);
    }

    /**
     * 静态工厂方法：创建简化版滑动变焦路径（默认向前）
     */
    public static DollyZoomPath createSimpleDollyZoomPath(Vec3 startPosition, Vec3 targetPoint, double duration) {
        return createSimpleDollyZoomPath(startPosition, targetPoint, duration, true);
    }

    /**
     * 静态工厂方法：创建高级版滑动变焦路径，支持后景位置
     * 根据后景位置自动计算参考FOV，创造更强的空间压缩效果
     * 摄像机在聚焦点前1格停下
     */
    public static DollyZoomPath createAdvancedDollyZoomPath(Vec3 startPosition, Vec3 targetPoint, Vec3 backgroundPoint, double duration, boolean forward) {
        Vec3 actualStartPosition;
        Vec3 actualEndPosition;
        double referenceDistance;
        double referenceFOV;
        
        if (forward) {
            // 向前移动：从起始位置向目标点前1格移动，FOV增大
            Vec3 direction = targetPoint.subtract(startPosition).normalize();
            double totalDistance = startPosition.distanceTo(targetPoint) - STOP_DISTANCE;
            actualStartPosition = startPosition;
            actualEndPosition = startPosition.add(direction.scale(totalDistance));
            referenceDistance = STOP_DISTANCE; // 终点距离作为参考距离
            
            // 计算后景位置相对于目标点的距离
            double backgroundDistance = targetPoint.distanceTo(backgroundPoint);
            
            // 根据后景距离计算参考FOV，创造更强的空间压缩感
            referenceFOV = 110.0 * (1 + backgroundDistance / 200); // 背景距离每增加200格，FOV增加110度
        } else {
            // 向后移动：从目标点前1格向起始位置移动，FOV减小，摄像机始终朝向目标点
            Vec3 direction = targetPoint.subtract(startPosition).normalize();
            double totalDistance = startPosition.distanceTo(targetPoint) - STOP_DISTANCE;
            actualStartPosition = startPosition.add(direction.scale(totalDistance)); // 目标点前1格位置
            actualEndPosition = startPosition; // 回到起始位置
            referenceDistance = actualStartPosition.distanceTo(targetPoint); // 起点距离作为参考距离（目标点前1格）
            
            // 计算后景位置相对于目标点的距离
            double backgroundDistance = targetPoint.distanceTo(backgroundPoint);
            
            // 向后移动时，参考FOV从靠近目标点的大FOV开始减小
            referenceFOV = 110.0 * (1 + backgroundDistance / 200); // 起点目标FOV（较大，因为距离近）
        }
        
        // 确保参考FOV在合理范围内
        referenceFOV = Math.max(5.0, Math.min(120.0, referenceFOV));
        
        // 计算起始和结束旋转角度（始终朝向目标点）
        Vec3 startRotation = calculateTargetRotation(targetPoint, actualStartPosition);
        Vec3 endRotation = calculateTargetRotation(targetPoint, actualEndPosition);
        
        return new DollyZoomPath(actualStartPosition, actualEndPosition, targetPoint, startRotation, endRotation, duration,
                               referenceDistance, referenceFOV);
    }

    /**
     * 静态工厂方法：创建高级版滑动变焦路径（默认向前）
     */
    public static DollyZoomPath createAdvancedDollyZoomPath(Vec3 startPosition, Vec3 targetPoint, Vec3 backgroundPoint, double duration) {
        return createAdvancedDollyZoomPath(startPosition, targetPoint, backgroundPoint, duration, true);
    }
    
    /**
     * 静态工厂方法：创建增强版滑动变焦路径，支持控制变焦强度
     * 保留强度参数，供高级用户使用
     */
    public static DollyZoomPath createDollyZoomPathWithStrength(Vec3 startPosition, Vec3 targetPoint, double duration, double strength, boolean forward) {
        Vec3 actualStartPosition;
        Vec3 actualEndPosition;
        double referenceDistance;
        double referenceFOV;
        
        if (forward) {
            // 向前移动：从起始位置向目标点前1格移动，FOV增大
            Vec3 direction = targetPoint.subtract(startPosition).normalize();
            double totalDistance = startPosition.distanceTo(targetPoint) - STOP_DISTANCE;
            actualStartPosition = startPosition;
            actualEndPosition = startPosition.add(direction.scale(totalDistance));
            referenceDistance = STOP_DISTANCE; // 使用终点距离作为参考距离
            referenceFOV = 110.0 * strength; // 根据强度调整参考FOV
        } else {
            // 向后移动：从目标点前1格向起始位置移动，FOV减小，摄像机始终朝向目标点
            Vec3 direction = targetPoint.subtract(startPosition).normalize();
            double totalDistance = startPosition.distanceTo(targetPoint) - STOP_DISTANCE;
            actualStartPosition = startPosition.add(direction.scale(totalDistance)); // 目标点前1格位置
            actualEndPosition = startPosition; // 回到起始位置
            referenceDistance = actualStartPosition.distanceTo(targetPoint); // 起点距离作为参考距离（目标点前1格）
            referenceFOV = 110.0 * strength; // 根据强度调整参考FOV（较大，因为距离近）
        }
        
        // 确保参考FOV在合理范围内
        referenceFOV = Math.max(5.0, Math.min(120.0, referenceFOV));
        
        // 计算起始和结束旋转角度（始终朝向目标点）
        Vec3 startRotation = calculateTargetRotation(targetPoint, actualStartPosition);
        Vec3 endRotation = calculateTargetRotation(targetPoint, actualEndPosition);
        
        return new DollyZoomPath(actualStartPosition, actualEndPosition, targetPoint, startRotation, endRotation, duration,
                               referenceDistance, referenceFOV);
    }

    /**
     * 静态工厂方法：创建增强版滑动变焦路径（默认向前）
     */
    public static DollyZoomPath createDollyZoomPathWithStrength(Vec3 startPosition, Vec3 targetPoint, double duration, double strength) {
        return createDollyZoomPathWithStrength(startPosition, targetPoint, duration, strength, true);
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