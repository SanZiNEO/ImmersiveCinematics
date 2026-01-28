package com.example.immersive_cinematics.director;

import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import com.example.immersive_cinematics.handler.CinematicManager;
import com.example.immersive_cinematics.script.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本执行引擎
 * 支持执行多运镜规则和灵活属性配置的镜头脚本
 */
public class TimelineProcessor {

    private static final Logger LOGGER = LogManager.getLogger();
    private static TimelineProcessor instance;

    // 每个玩家对应一个脚本执行上下文
    private Map<LocalPlayer, ScriptExecutionContext> executionContexts;
    private CinematicManager cinematicManager;

    public TimelineProcessor() {
        executionContexts = new HashMap<>();
        cinematicManager = CinematicManager.getInstance();
    }

    public static TimelineProcessor getInstance() {
        if (instance == null) {
            instance = new TimelineProcessor();
        }
        return instance;
    }

    // 开始执行镜头脚本
    public void startCameraScript(LocalPlayer player, String scriptName) {
        if (player == null) {
            LOGGER.warn("Cannot start camera script - player is null");
            return;
        }

        // 获取要执行的脚本
        CameraScript script = CameraScriptStorage.getInstance().getScript(scriptName);
        if (script == null) {
            LOGGER.warn("Camera script not found: " + scriptName);
            return;
        }

        // 创建执行上下文
        ScriptExecutionContext context = new ScriptExecutionContext(script);
        executionContexts.put(player, context);
        context.startExecution();

        LOGGER.info("Started camera script '{}' for player '{}'", scriptName, player.getName().getString());
    }

    // 停止执行镜头脚本
    public void stopCameraScript(LocalPlayer player, boolean force) {
        ScriptExecutionContext context = executionContexts.get(player);
        if (context != null) {
            context.stopExecution(force);
            executionContexts.remove(player);
            LOGGER.info("Stopped camera script for player '{}'", player.getName().getString());
        }
    }

    // 执行帧更新
    public void update(LocalPlayer player, float partialTicks) {
        ScriptExecutionContext context = executionContexts.get(player);
        if (context != null) {
            context.update(player, partialTicks);
            // 如果脚本执行完毕，自动移除上下文
            if (context.isExecutionComplete()) {
                executionContexts.remove(player);
                LOGGER.info("Camera script execution completed for player '{}'", player.getName().getString());
            }
        }
    }

    // 检查脚本是否正在执行
    public boolean isCameraScriptActive(LocalPlayer player) {
        ScriptExecutionContext context = executionContexts.get(player);
        return context != null && context.isExecutionActive();
    }

    // 获取当前执行的脚本信息
    public String getCurrentCameraScriptInfo(LocalPlayer player) {
        ScriptExecutionContext context = executionContexts.get(player);
        if (context != null) {
            CameraScript script = context.getScript();
            CameraScript.ShotRule currentRule = context.getCurrentShotRule();
            CameraScript.MovementRoute currentRoute = context.getCurrentRoute();
            
            StringBuilder info = new StringBuilder();
            info.append("脚本: ").append(script.getName());
            
            if (currentRule != null) {
                info.append(" | 规则: ").append(currentRule.getRuleName());
                
                if (currentRoute != null) {
                    info.append(" | 路线: ").append(currentRoute.getRouteName() != null ? currentRoute.getRouteName() : "未命名");
                    info.append(" | 进度: ").append(String.format("%.1f%%", context.getRouteProgress() * 100));
                }
            }
            
            return info.toString();
        }
        return null;
    }

    // 脚本执行上下文类
    private class ScriptExecutionContext {

        private CameraScript script;
        private int currentRuleIndex;
        private int currentRouteIndex;
        private long ruleStartTime;
        private long routeStartTime;
        private boolean isExecutionActive;
        private boolean isExecutionComplete;

        public ScriptExecutionContext(CameraScript script) {
            this.script = script;
            this.currentRuleIndex = 0;
            this.currentRouteIndex = 0;
            this.ruleStartTime = -1;
            this.routeStartTime = -1;
            this.isExecutionActive = false;
            this.isExecutionComplete = false;
        }

        public CameraScript getScript() {
            return script;
        }

        public CameraScript.ShotRule getCurrentShotRule() {
            if (currentRuleIndex < script.getShotRules().size()) {
                return script.getShotRules().get(currentRuleIndex);
            }
            return null;
        }

        public CameraScript.MovementRoute getCurrentRoute() {
            CameraScript.ShotRule currentRule = getCurrentShotRule();
            if (currentRule != null && currentRouteIndex < currentRule.getRoutes().size()) {
                return currentRule.getRoutes().get(currentRouteIndex);
            }
            return null;
        }

        public boolean isExecutionActive() {
            return isExecutionActive;
        }

        public boolean isExecutionComplete() {
            return isExecutionComplete;
        }

        public double getRouteProgress() {
            CameraScript.MovementRoute currentRoute = getCurrentRoute();
            if (currentRoute != null && routeStartTime != -1) {
                double elapsedTime = (Minecraft.getInstance().level.getGameTime() - routeStartTime) / 20.0;
                return Math.min(elapsedTime / currentRoute.getDuration(), 1.0);
            }
            return 0.0;
        }

        public void startExecution() {
            isExecutionActive = true;
            isExecutionComplete = false;
            currentRuleIndex = 0;
            currentRouteIndex = 0;
            ruleStartTime = Minecraft.getInstance().level.getGameTime();
        }

        public void stopExecution(boolean force) {
            isExecutionActive = false;
            // 停止运镜
            cinematicManager.stopCurrentMovement();
            // 恢复玩家视角
            if (cinematicManager.isCinematicActive()) {
                cinematicManager.stopCinematic();
            }
        }

        public void update(LocalPlayer player, float partialTicks) {
            if (!isExecutionActive) {
                return;
            }

            // 检查运镜规则是否已结束
            if (currentRuleIndex >= script.getShotRules().size()) {
                isExecutionComplete = true;
                isExecutionActive = false;
                cinematicManager.stopCinematic();
                return;
            }

            CameraScript.ShotRule currentRule = script.getShotRules().get(currentRuleIndex);

            // 检查运镜规则延迟
            long currentTime = Minecraft.getInstance().level.getGameTime();
            if (ruleStartTime == -1) {
                ruleStartTime = currentTime;
            } else if (currentTime - ruleStartTime < currentRule.getDelay() * 20) {
                // 延迟期间，等待
                return;
            }

            // 执行运镜路线
            if (currentRouteIndex < currentRule.getRoutes().size()) {
                CameraScript.MovementRoute currentRoute = currentRule.getRoutes().get(currentRouteIndex);
                
                // 启动新路线
                if (routeStartTime == -1) {
                    startMovementRoute(player, currentRoute);
                    routeStartTime = currentTime;
                }

                // 检查路线是否结束
                double elapsedTime = (currentTime - routeStartTime) / 20.0;
                if (elapsedTime >= currentRoute.getDuration()) {
                    currentRouteIndex++;
                    routeStartTime = -1;
                    
                    // 如果是循环规则，重置路线索引
                    if (currentRouteIndex >= currentRule.getRoutes().size()) {
                        if (currentRule.isLooping()) {
                            currentRouteIndex = 0;
                        } else {
                            // 进入下一个规则
                            currentRuleIndex++;
                            routeStartTime = -1;
                            ruleStartTime = -1;
                        }
                    }
                }
            } else {
                // 规则已完成
                currentRuleIndex++;
                currentRouteIndex = 0;
                routeStartTime = -1;
                ruleStartTime = -1;
            }
        }

        private void startMovementRoute(LocalPlayer player, CameraScript.MovementRoute route) {
            // 根据路径类型创建对应的IMovementPath
            IMovementPath path = createMovementPath(route);
            
            if (path != null) {
                // 设置FOV范围
                path.setFOVRange(route.getStartFOV(), route.getEndFOV());
                
                // 执行路径
                cinematicManager.executePath(path, route.getDuration());
            }
        }

        private IMovementPath createMovementPath(CameraScript.MovementRoute route) {
            Vec3 startPos = route.getStartPosition();
            Vec3 endPos = route.getEndPosition();
            Vec3 controlPos = route.getControlPosition();
            Vec3 targetPoint = route.getTargetPoint();
            Vec3 backgroundPoint = route.getBackgroundPoint();

            // 如果没有指定起始位置，使用玩家当前位置
            if (startPos == null && Minecraft.getInstance().player != null) {
                startPos = Minecraft.getInstance().player.position();
            }

            // 根据路径类型创建对应的IMovementPath
            switch (route.getPathType()) {
                case DIRECT:
                    if (startPos != null && endPos != null) {
                        return new DirectLinearPath(startPos, endPos, route.getDuration());
                    }
                    break;

                case SMOOTH:
                    if (startPos != null && endPos != null) {
                        Vec3 startRotation = new Vec3(
                                Math.toRadians(route.getStartPitch()),
                                Math.toRadians(route.getStartYaw()),
                                0
                        );
                        Vec3 endRotation = new Vec3(
                                Math.toRadians(route.getEndPitch()),
                                Math.toRadians(route.getEndYaw()),
                                0
                        );
                        return new SmoothLinearPath(startPos, endPos, startRotation, endRotation, route.getDuration());
                    }
                    break;

                case ORBIT:
                    if (targetPoint != null) {
                        OrbitPath orbitPath = new OrbitPath(
                                targetPoint,
                                route.getRadius(),
                                route.getSpeed(),
                                route.getHeight(),
                                route.getDuration()
                        );
                        // 应用配置文件中的 heading 作为朝向偏移
                        orbitPath.setHeadingOffset(Math.toRadians(route.getHeading()));
                        return orbitPath;
                    }
                    break;

                case BEZIER:
                    if (startPos != null && endPos != null && controlPos != null) {
                        Vec3 startRotation = new Vec3(
                                Math.toRadians(route.getStartPitch()),
                                Math.toRadians(route.getStartYaw()),
                                0
                        );
                        Vec3 endRotation = new Vec3(
                                Math.toRadians(route.getEndPitch()),
                                Math.toRadians(route.getEndYaw()),
                                0
                        );
                        return new BezierPath(startPos, controlPos, endPos, startRotation, endRotation, route.getDuration());
                    }
                    break;

                case SPIRAL:
                    if (targetPoint != null) {
                        return new SpiralPath(
                                targetPoint,
                                route.getRadius(),
                                route.getSpeed(),
                                route.getHeight(),
                                route.getDuration()
                        );
                    }
                    break;

                case DOLLY_ZOOM:
                    if (startPos != null && targetPoint != null) {
                        Vec3 startRotation = new Vec3(
                                Math.toRadians(route.getStartPitch()),
                                Math.toRadians(route.getStartYaw()),
                                0
                        );
                        Vec3 endRotation = calculateTargetRotation(targetPoint, endPos != null ? endPos : startPos);
                        
                        if (endPos != null) {
                            return new DollyZoomPath(startPos, endPos, targetPoint, startRotation, endRotation, route.getDuration());
                        } else {
                            return DollyZoomPath.createSimpleDollyZoomPath(startPos, targetPoint, route.getDuration(), route.isForward());
                        }
                    }
                    break;

                case STATIONARY_PAN:
                    if (startPos != null) {
                        Vec3 startRotation = new Vec3(
                                Math.toRadians(route.getStartPitch()),
                                Math.toRadians(route.getStartYaw()),
                                0
                        );
                        Vec3 endRotation = new Vec3(
                                Math.toRadians(route.getEndPitch()),
                                Math.toRadians(route.getEndYaw()),
                                0
                        );
                        return new StationaryPanPath(startPos, startRotation, endRotation, route.getDuration());
                    }
                    break;
            }

            LOGGER.warn("Could not create movement path for route: " + (route.getRouteName() != null ? route.getRouteName() : "Unnamed"));
            return null;
        }

        private Vec3 calculateTargetRotation(Vec3 target, Vec3 cameraPosition) {
            double deltaX = target.x - cameraPosition.x;
            double deltaZ = target.z - cameraPosition.z;
            double deltaY = target.y - cameraPosition.y;
            
            double yaw = Math.atan2(deltaZ, deltaX) - Math.PI / 2;
            double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            double pitch = -Math.atan2(deltaY, horizontalDistance);
            
            return new Vec3(pitch, yaw, 0);
        }
    }
}