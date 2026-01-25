package com.example.immersive_cinematics.handler;

import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import com.example.immersive_cinematics.script.IMovementPath;
import com.example.immersive_cinematics.script.OrbitPath;
import com.example.immersive_cinematics.script.DirectLinearPath;
import com.example.immersive_cinematics.script.SmoothLinearPath;
import com.example.immersive_cinematics.script.BezierPath;
import com.example.immersive_cinematics.script.SpiralPath;
import com.example.immersive_cinematics.script.DollyZoomPath;
import com.example.immersive_cinematics.script.StationaryPanPath;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public class CinematicManager {

    private static CinematicManager instance;

    private CinematicCameraEntity virtualCamera;
    private boolean isCinematicActive = false;

    // 运动系统变量
    private IMovementPath currentMovementPath;
    private long movementStartGameTime; // 运镜开始时的游戏刻数
    private double movementDuration; // 秒
    private boolean isMovementActive = false;
    
    // 旋转平滑插值辅助变量
    private float lastYaw;  // 上一帧的偏航角（度）
    private float lastPitch; // 上一帧的俯仰角（度）
    // FOV控制变量
    private double currentFOV = 70.0; // 当前FOV值（度）
    private double targetFOV = 70.0; // 目标FOV值（度）
    
    // 自定义摄像机设置变量
    private Vec3 customCameraPosition = null; // 自定义摄像机位置
    private Float customCameraYaw = null; // 自定义摄像机偏航角（度）
    private Float customCameraPitch = null; // 自定义摄像机俯仰角（度）
    private Double customCameraFOV = null; // 自定义摄像机FOV（度）

    private CinematicManager() {
    }

    public static CinematicManager getInstance() {
        if (instance == null) {
            instance = new CinematicManager();
        }
        return instance;
    }

    public void startCinematic() {
        if (isCinematicActive || Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
            return;
        }

        // 创建虚拟摄像机实体
        virtualCamera = new CinematicCameraEntity(Minecraft.getInstance().player.getId() + 1000);
        virtualCamera.spawn();

        // 设置摄像机位置
        if (customCameraPosition != null) {
            virtualCamera.setPos(customCameraPosition.x, customCameraPosition.y, customCameraPosition.z);
        } else {
            // 默认位置：玩家头顶 10 格
            double playerX = Minecraft.getInstance().player.getX();
            double playerY = Minecraft.getInstance().player.getY() + 10;
            double playerZ = Minecraft.getInstance().player.getZ();
            virtualCamera.setPos(playerX, playerY, playerZ);
        }

        // 设置摄像机角度
        if (customCameraYaw != null) {
            virtualCamera.setYRot(customCameraYaw);
        } else {
            virtualCamera.setYRot(Minecraft.getInstance().player.getYRot());
        }
        
        if (customCameraPitch != null) {
            virtualCamera.setXRot(customCameraPitch);
        } else {
            virtualCamera.setXRot(Minecraft.getInstance().player.getXRot());
        }

        // 设置摄像机FOV
        if (customCameraFOV != null) {
            currentFOV = customCameraFOV;
            targetFOV = customCameraFOV;
        } else {
            currentFOV = 70.0;
            targetFOV = 70.0;
        }

        // 设置为当前摄像机
        Minecraft.getInstance().setCameraEntity(virtualCamera);
        isCinematicActive = true;
    }

    public void stopCinematic() {
        if (!isCinematicActive || Minecraft.getInstance().player == null) {
            return;
        }

        // 恢复玩家摄像机
        Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player);

        // 销毁虚拟摄像机
        if (virtualCamera != null) {
            virtualCamera.despawn();
            virtualCamera = null;
        }

        isCinematicActive = false;
    }

    public void toggleCinematic() {
        if (isCinematicActive) {
            stopCinematic();
        } else {
            startCinematic();
        }
    }

    public boolean isCinematicActive() {
        return isCinematicActive;
    }

    public CinematicCameraEntity getVirtualCamera() {
        return virtualCamera;
    }

    public net.minecraft.client.player.LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    public boolean isCinematicMode() {
        return isCinematicActive;
    }

    // 获取已流逝的时间（ticks）- 不包含 partialTicks
    public double getElapsedTicks() {
        if (!isMovementActive) return 0;
        long currentGameTime = Minecraft.getInstance().level.getGameTime();
        return currentGameTime - movementStartGameTime;
    }

    // 渲染更新方法 - 使用 partialTicks 实现帧级精度
    public void onRenderUpdate(double partialTicks) {
        if (isCinematicActive && virtualCamera != null && Minecraft.getInstance().player != null) {
            // 如果有运动路径正在执行
            if (isMovementActive && currentMovementPath != null) {
                // 计算精确时间（当前 tick 时间 + 帧内插值时间）
                double baseTime = getElapsedTicks();
                double preciseTime = baseTime + partialTicks;

                // 获取当前时间的位置和旋转
                Vec3 position = currentMovementPath.getPosition(preciseTime);
                Vec3 rotation = currentMovementPath.getRotation(preciseTime);

                // 位置更新：强制对齐到精确坐标，彻底关闭原版插值
                virtualCamera.setPos(position.x, position.y, position.z);
                virtualCamera.xo = position.x;
                virtualCamera.yo = position.y;
                virtualCamera.zo = position.z;

                // 旋转更新：强制对齐到精确角度，彻底关闭原版插值
                float currentPitch = (float) Math.toDegrees(rotation.x);
                float currentYaw = (float) Math.toDegrees(rotation.y);
                virtualCamera.setXRot(currentPitch);
                virtualCamera.setYRot(currentYaw);
                virtualCamera.xRotO = currentPitch;
                virtualCamera.yRotO = currentYaw;
                
                // FOV更新：从路径获取FOV值并应用
                targetFOV = currentMovementPath.getFOV(preciseTime);
                currentFOV = targetFOV; // 直接应用，不插值，因为路径已经处理了插值

                // 运动结束处理
                double durationInTicks = movementDuration * 20.0;
                if (preciseTime >= durationInTicks) {
                    isMovementActive = false;
                    currentMovementPath = null;
                    // 自动退出电影模式，恢复玩家视角
                    stopCinematic();
                }
            } else {
                // 没有运动时，保持在自定义位置或默认位置
                if (customCameraPosition != null) {
                    virtualCamera.setPos(customCameraPosition.x, customCameraPosition.y, customCameraPosition.z);
                    virtualCamera.xo = customCameraPosition.x;
                    virtualCamera.yo = customCameraPosition.y;
                    virtualCamera.zo = customCameraPosition.z;
                } else {
                    // 默认位置：玩家头顶 10 格
                    double playerX = Minecraft.getInstance().player.getX();
                    double playerY = Minecraft.getInstance().player.getY() + 10;
                    double playerZ = Minecraft.getInstance().player.getZ();
                    virtualCamera.setPos(playerX, playerY, playerZ);
                    virtualCamera.xo = playerX;
                    virtualCamera.yo = playerY;
                    virtualCamera.zo = playerZ;
                }
                
                // 设置摄像机角度
                if (customCameraYaw != null) {
                    virtualCamera.setYRot(customCameraYaw);
                    virtualCamera.yRotO = customCameraYaw;
                }
                if (customCameraPitch != null) {
                    virtualCamera.setXRot(customCameraPitch);
                    virtualCamera.xRotO = customCameraPitch;
                }
                
                // 没有运动时，使用自定义FOV或默认FOV
                if (customCameraFOV != null) {
                    currentFOV = customCameraFOV;
                    targetFOV = customCameraFOV;
                } else {
                    currentFOV = 70.0;
                    targetFOV = 70.0;
                }
            }
        }
    }
    
    // 获取当前FOV值
    public double getCurrentFOV() {
        return currentFOV;
    }
    
    // 设置自定义FOV范围（如果路径支持）
    public void setFOVRange(float startFOV, float endFOV) {
        if (currentMovementPath != null) {
            currentMovementPath.setFOVRange(startFOV, endFOV);
        }
        // 同时更新本地变量
        this.targetFOV = endFOV;
    }
    
    // 设置朝向偏移（如果路径支持）
    public void setHeadingOffset(double offsetInDegrees) {
        if (currentMovementPath != null) {
            currentMovementPath.setHeadingOffset(Math.toRadians(offsetInDegrees));
        }
    }

    // 启动直接朝向目标的直线运动
    public void startDirectLinearMovement(Vec3 startPos, Vec3 endPos, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = new DirectLinearPath(startPos, endPos, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动平滑曲线运动（带视角过渡）
    public void startSmoothLinearMovement(Vec3 startPos, Vec3 endPos, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        Vec3 startRotation = new Vec3(
                Math.toRadians(Minecraft.getInstance().player.getXRot()),
                Math.toRadians(Minecraft.getInstance().player.getYRot()),
                0
        );

        // 计算终点朝向
        double deltaX = endPos.x - startPos.x;
        double deltaZ = endPos.z - startPos.z;
        double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;

        double deltaY = endPos.y - startPos.y;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        Vec3 endRotation = new Vec3(pitch, yaw, 0);

        currentMovementPath = new SmoothLinearPath(startPos, endPos, startRotation, endRotation, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动环绕运动
    public void startOrbitMovement(Vec3 centerPos, double radius, double speed, double height, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = new OrbitPath(centerPos, radius, speed, height, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 通用执行路径方法（策略模式核心）
    public void executePath(IMovementPath path, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = path;
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动贝塞尔曲线运动
    public void startBezierMovement(Vec3 startPos, Vec3 controlPos, Vec3 endPos, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        Vec3 startRotation = new Vec3(
                Math.toRadians(Minecraft.getInstance().player.getXRot()),
                Math.toRadians(Minecraft.getInstance().player.getYRot()),
                0
        );

        // 计算终点朝向
        double deltaX = endPos.x - startPos.x;
        double deltaZ = endPos.z - startPos.z;
        double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;

        double deltaY = endPos.y - startPos.y;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = -Math.atan2(deltaY, horizontalDistance);

        Vec3 endRotation = new Vec3(pitch, yaw, 0);

        currentMovementPath = new BezierPath(startPos, controlPos, endPos, startRotation, endRotation, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动螺旋上升/下降运动
    public void startSpiralMovement(Vec3 centerPos, double radius, double speed, double height, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = new SpiralPath(centerPos, radius, speed, height, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动滑动变焦运动（标准版本）
    public void startDollyZoomMovement(Vec3 startPos, Vec3 endPos, Vec3 targetPoint, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        Vec3 startRotation = new Vec3(
                Math.toRadians(Minecraft.getInstance().player.getXRot()),
                Math.toRadians(Minecraft.getInstance().player.getYRot()),
                0
        );

        Vec3 endRotation = calculateTargetRotation(targetPoint, endPos);

        currentMovementPath = new DollyZoomPath(startPos, endPos, targetPoint, startRotation, endRotation, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动简化版滑动变焦运动（推荐）
    public void startSimpleDollyZoomMovement(Vec3 startPos, Vec3 targetPoint, double distance, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = DollyZoomPath.createSimpleDollyZoomPath(startPos, targetPoint, distance, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动增强版滑动变焦运动（支持变焦强度控制）
    public void startDollyZoomMovementWithStrength(Vec3 startPos, Vec3 targetPoint, double distance, double duration, double strength) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = DollyZoomPath.createDollyZoomPathWithStrength(startPos, targetPoint, distance, duration, strength);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 启动静态旋转/摇镜头运动
    public void startStationaryPanMovement(Vec3 fixedPosition, Vec3 startRotation, Vec3 endRotation, double duration) {
        if (!isCinematicActive && Minecraft.getInstance().player != null) {
            startCinematic();
        }

        currentMovementPath = new StationaryPanPath(fixedPosition, startRotation, endRotation, duration);
        movementStartGameTime = Minecraft.getInstance().level.getGameTime();
        movementDuration = duration;
        isMovementActive = true;
    }

    // 停止当前运动
    public void stopCurrentMovement() {
        isMovementActive = false;
        currentMovementPath = null;
    }

    public boolean isMovementActive() {
        return isMovementActive;
    }

    public IMovementPath getCurrentMovementPath() {
        return currentMovementPath;
    }

    public double getMovementDuration() {
        return movementDuration;
    }
    
    // 设置自定义摄像机位置
    public void setCustomCameraPosition(Vec3 position) {
        this.customCameraPosition = position;
        // 如果摄像机已经激活，立即更新位置
        if (isCinematicActive && virtualCamera != null) {
            virtualCamera.setPos(position.x, position.y, position.z);
            virtualCamera.xo = position.x;
            virtualCamera.yo = position.y;
            virtualCamera.zo = position.z;
        }
    }
    
    // 设置自定义摄像机角度
    public void setCustomCameraRotation(float yaw, float pitch) {
        this.customCameraYaw = yaw;
        this.customCameraPitch = pitch;
        // 如果摄像机已经激活，立即更新角度
        if (isCinematicActive && virtualCamera != null) {
            virtualCamera.setYRot(yaw);
            virtualCamera.setXRot(pitch);
            virtualCamera.yRotO = yaw;
            virtualCamera.xRotO = pitch;
        }
    }
    
    // 设置自定义摄像机FOV
    public void setCustomCameraFOV(double fov) {
        this.customCameraFOV = fov;
        this.currentFOV = fov;
        this.targetFOV = fov;
    }
    
    // 重置自定义摄像机设置
    public void resetCustomCameraSettings() {
        this.customCameraPosition = null;
        this.customCameraYaw = null;
        this.customCameraPitch = null;
        this.customCameraFOV = null;
        // 如果摄像机已经激活，重置到默认设置
        if (isCinematicActive && virtualCamera != null && Minecraft.getInstance().player != null) {
            double playerX = Minecraft.getInstance().player.getX();
            double playerY = Minecraft.getInstance().player.getY() + 10;
            double playerZ = Minecraft.getInstance().player.getZ();
            virtualCamera.setPos(playerX, playerY, playerZ);
            virtualCamera.xo = playerX;
            virtualCamera.yo = playerY;
            virtualCamera.zo = playerZ;
            
            virtualCamera.setYRot(Minecraft.getInstance().player.getYRot());
            virtualCamera.setXRot(Minecraft.getInstance().player.getXRot());
            virtualCamera.yRotO = Minecraft.getInstance().player.getYRot();
            virtualCamera.xRotO = Minecraft.getInstance().player.getXRot();
            
            currentFOV = 70.0;
            targetFOV = 70.0;
        }
    }
    
    // 获取自定义摄像机位置
    public Vec3 getCustomCameraPosition() {
        return customCameraPosition;
    }
    
    // 获取自定义摄像机偏航角
    public Float getCustomCameraYaw() {
        return customCameraYaw;
    }
    
    // 获取自定义摄像机俯仰角
    public Float getCustomCameraPitch() {
        return customCameraPitch;
    }
    
    // 获取自定义摄像机FOV
    public Double getCustomCameraFOV() {
        return customCameraFOV;
    }
    
    /**
     * 角度插值函数（支持最短路径插值）- 类似 Minecraft 的 MathHelper.lerpAngle
     */
    private double lerpAngle(double start, double end, double t) {
        double delta = end - start;
        
        while (delta > Math.PI) {
            delta -= 2 * Math.PI;
        }
        while (delta < -Math.PI) {
            delta += 2 * Math.PI;
        }
        
        return start + delta * t;
    }

    /**
     * 计算目标位置的旋转角度（辅助方法）
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
}
