package com.example.immersive_cinematics.util;

import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import com.example.immersive_cinematics.director.CameraScript;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 相机状态记录工具类
 * 用于在电影模式下记录相机的完整状态
 */
@OnlyIn(Dist.CLIENT)
public class CameraStateRecorder {

    private static CameraState lastRecordedState;
    private static boolean isRecordingEnabled;

    /**
     * 相机状态类
     * 包含完整的相机属性
     */
    public static class CameraState {
        private Vec3 position;
        private float yaw;
        private float pitch;
        private float fov;
        private float roll;
        private Vec3 targetPoint;
        private float movementSpeed;
        private float sensitivity;
        private boolean isCinematicMode;

        public CameraState() {
            this.position = Vec3.ZERO;
            this.yaw = 0;
            this.pitch = 0;
            this.fov = 70;
            this.roll = 0;
            this.targetPoint = Vec3.ZERO;
            this.movementSpeed = 1.0f;
            this.sensitivity = 1.0f;
            this.isCinematicMode = false;
        }

        public Vec3 getPosition() {
            return position;
        }

        public void setPosition(Vec3 position) {
            this.position = position;
        }

        public float getYaw() {
            return yaw;
        }

        public void setYaw(float yaw) {
            this.yaw = yaw;
        }

        public float getPitch() {
            return pitch;
        }

        public void setPitch(float pitch) {
            this.pitch = pitch;
        }

        public float getFov() {
            return fov;
        }

        public void setFov(float fov) {
            this.fov = fov;
        }

        public float getRoll() {
            return roll;
        }

        public void setRoll(float roll) {
            this.roll = roll;
        }

        public Vec3 getTargetPoint() {
            return targetPoint;
        }

        public void setTargetPoint(Vec3 targetPoint) {
            this.targetPoint = targetPoint;
        }

        public float getMovementSpeed() {
            return movementSpeed;
        }

        public void setMovementSpeed(float movementSpeed) {
            this.movementSpeed = movementSpeed;
        }

        public float getSensitivity() {
            return sensitivity;
        }

        public void setSensitivity(float sensitivity) {
            this.sensitivity = sensitivity;
        }

        public boolean isCinematicMode() {
            return isCinematicMode;
        }

        public void setCinematicMode(boolean cinematicMode) {
            isCinematicMode = cinematicMode;
        }

        /**
         * 转换为运镜路线
         */
        public CameraScript.MovementRoute toMovementRoute() {
            CameraScript.MovementRoute route = new CameraScript.MovementRoute();
            route.setStartPosition(this.position);
            route.setStartFOV(this.fov);
            route.setStartYaw(this.yaw);
            route.setStartPitch(this.pitch);
            route.setTargetPoint(this.targetPoint);
            return route;
        }

        @Override
        public String toString() {
            return String.format(
                    "CameraState{x=%.2f, y=%.2f, z=%.2f, yaw=%.1f, pitch=%.1f, fov=%.1f, roll=%.1f}",
                    position.x, position.y, position.z, yaw, pitch, fov, roll
            );
        }
    }

    /**
     * 启用/禁用相机状态记录
     */
    public static void setRecordingEnabled(boolean enabled) {
        isRecordingEnabled = enabled;
    }

    /**
     * 检查是否启用了状态记录
     */
    public static boolean isRecordingEnabled() {
        return isRecordingEnabled;
    }

    /**
     * 记录当前相机状态
     */
    public static CameraState recordCurrentState() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        CameraState state = new CameraState();

        if (player != null) {
            state.setPosition(player.position());
            state.setYaw(player.getYRot());
            state.setPitch(player.getXRot());
            
            if (minecraft.options != null) {
                state.setFov(minecraft.options.fov().get());
            }
            
            state.setCinematicMode(false);
        }

        if (minecraft.level != null) {
            for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof CinematicCameraEntity cameraEntity) {
                    state.setPosition(cameraEntity.position());
                    state.setYaw(cameraEntity.getYRot());
                    state.setPitch(cameraEntity.getXRot());
                    state.setFov(cameraEntity.getCurrentFov());
                    state.setCinematicMode(true);
                    break;
                }
            }
        }

        lastRecordedState = state;
        return state;
    }

    public static CameraState getLastRecordedState() {
        return lastRecordedState;
    }

    public static CameraScript.MovementRoute createRouteFromStates(CameraState startState, CameraState endState, CameraScript.PathType pathType) {
        CameraScript.MovementRoute route = startState.toMovementRoute();
        
        route.setEndPosition(endState.getPosition());
        route.setEndFOV(endState.getFov());
        route.setEndYaw(endState.getYaw());
        route.setEndPitch(endState.getPitch());
        route.setPathType(pathType);
        route.setDuration(5.0);

        switch (pathType) {
            case ORBIT:
            case SPIRAL:
            case DOLLY_ZOOM:
                route.setTargetPoint(endState.getPosition());
                break;
            case BEZIER:
                Vec3 midPoint = new Vec3(
                        (startState.getPosition().x + endState.getPosition().x) / 2,
                        (startState.getPosition().y + endState.getPosition().y) / 2 + 5,
                        (startState.getPosition().z + endState.getPosition().z) / 2
                );
                route.setControlPosition(midPoint);
                break;
            case STATIONARY_PAN:
                route.setStartYaw(startState.getYaw());
                route.setStartPitch(startState.getPitch());
                route.setEndYaw(endState.getYaw());
                route.setEndPitch(endState.getPitch());
                break;
        }

        return route;
    }

    public static String getStateDetails(CameraState state) {
        if (state == null) {
            return "未记录相机状态";
        }
        
        StringBuilder details = new StringBuilder();
        details.append("位置: ").append(String.format("%.1f, %.1f, %.1f", state.getPosition().x, state.getPosition().y, state.getPosition().z));
        details.append("\n视角: ").append(String.format("Yaw: %.1f°, Pitch: %.1f°", state.getYaw(), state.getPitch()));
        details.append("\nFOV: ").append(String.format("%.1f°", state.getFov()));
        details.append("\n电影模式: ").append(state.isCinematicMode() ? "已启用" : "未启用");
        
        if (state.getTargetPoint() != null && !state.getTargetPoint().equals(Vec3.ZERO)) {
            details.append("\n目标点: ").append(String.format("%.1f, %.1f, %.1f", 
                    state.getTargetPoint().x, state.getTargetPoint().y, state.getTargetPoint().z));
        }
        
        return details.toString();
    }

    public static void reset() {
        lastRecordedState = null;
        isRecordingEnabled = false;
    }
}