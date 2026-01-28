package com.example.immersive_cinematics.director;

import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.FloatTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 镜头脚本数据模型
 * 支持多运镜规则和灵活的属性配置
 */
public class CameraScript {

    private String name;
    private String description;
    private UUID id;
    private List<ShotRule> shotRules; // 多个运镜规则

    public CameraScript() {
        this("", "");
    }
    
    public CameraScript(String name, String description) {
        this.name = name;
        this.description = description;
        this.id = UUID.randomUUID();
        this.shotRules = new ArrayList<>();
    }

    // 获取脚本名称
    public String getName() {
        return name;
    }

    // 设置脚本名称
    public void setName(String name) {
        this.name = name;
    }

    // 获取脚本描述
    public String getDescription() {
        return description;
    }

    // 设置脚本描述
    public void setDescription(String description) {
        this.description = description;
    }

    // 获取脚本ID
    public UUID getId() {
        return id;
    }

    // 获取所有运镜规则
    public List<ShotRule> getShotRules() {
        return shotRules;
    }

    // 添加运镜规则
    public void addShotRule(ShotRule shotRule) {
        this.shotRules.add(shotRule);
    }

    // 移除运镜规则
    public void removeShotRule(ShotRule shotRule) {
        this.shotRules.remove(shotRule);
    }

    // 获取运镜规则数量
    public int getShotRuleCount() {
        return shotRules.size();
    }

    // NBT序列化
    public CompoundTag toNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("name", name);
        nbt.putString("description", description);
        nbt.putString("id", id.toString());
        
        ListTag shotRulesTag = new ListTag();
        for (ShotRule rule : shotRules) {
            shotRulesTag.add(rule.toNBT());
        }
        nbt.put("shotRules", shotRulesTag);
        
        return nbt;
    }

    // NBT反序列化
    public static CameraScript fromNBT(CompoundTag nbt) {
        String name = nbt.getString("name");
        String description = nbt.getString("description");
        UUID id = UUID.fromString(nbt.getString("id"));
        
        CameraScript script = new CameraScript(name, description);
        script.id = id;
        
        ListTag shotRulesTag = nbt.getList("shotRules", 10);
        for (int i = 0; i < shotRulesTag.size(); i++) {
            CompoundTag ruleTag = shotRulesTag.getCompound(i);
            script.shotRules.add(ShotRule.fromNBT(ruleTag));
        }
        
        return script;
    }

    // 运镜规则类
    public static class ShotRule {
        private String ruleName;
        private List<MovementRoute> routes; // 多个运镜路线
        private List<TriggerCondition> triggerConditions; // 触发条件
        private boolean isLooping; // 是否循环
        private float delay; // 延迟（秒）

        public ShotRule() {
            this("");
        }
        
        public ShotRule(String ruleName) {
            this.ruleName = ruleName;
            this.routes = new ArrayList<>();
            this.triggerConditions = new ArrayList<>();
            this.isLooping = false;
            this.delay = 0;
        }

        public String getRuleName() {
            return ruleName;
        }

        public void setRuleName(String ruleName) {
            this.ruleName = ruleName;
        }

        public List<MovementRoute> getRoutes() {
            return routes;
        }

        public void addRoute(MovementRoute route) {
            this.routes.add(route);
        }

        public void removeRoute(MovementRoute route) {
            this.routes.remove(route);
        }

        public List<TriggerCondition> getTriggerConditions() {
            return triggerConditions;
        }

        public void addTriggerCondition(TriggerCondition condition) {
            this.triggerConditions.add(condition);
        }

        public void removeTriggerCondition(TriggerCondition condition) {
            this.triggerConditions.remove(condition);
        }

        public boolean isLooping() {
            return isLooping;
        }

        public void setLooping(boolean looping) {
            isLooping = looping;
        }

        public float getDelay() {
            return delay;
        }

        public void setDelay(float delay) {
            this.delay = delay;
        }

        public CompoundTag toNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("ruleName", ruleName);
            nbt.putBoolean("isLooping", isLooping);
            nbt.putFloat("delay", delay);
            
            ListTag routesTag = new ListTag();
            for (MovementRoute route : routes) {
                routesTag.add(route.toNBT());
            }
            nbt.put("routes", routesTag);
            
            ListTag triggersTag = new ListTag();
            for (TriggerCondition condition : triggerConditions) {
                triggersTag.add(condition.toNBT());
            }
            nbt.put("triggerConditions", triggersTag);
            
            return nbt;
        }

        public static ShotRule fromNBT(CompoundTag nbt) {
            String ruleName = nbt.getString("ruleName");
            ShotRule rule = new ShotRule(ruleName);
            rule.isLooping = nbt.getBoolean("isLooping");
            rule.delay = nbt.getFloat("delay");
            
            ListTag routesTag = nbt.getList("routes", 10);
            for (int i = 0; i < routesTag.size(); i++) {
                CompoundTag routeTag = routesTag.getCompound(i);
                rule.routes.add(MovementRoute.fromNBT(routeTag));
            }
            
            ListTag triggersTag = nbt.getList("triggerConditions", 10);
            for (int i = 0; i < triggersTag.size(); i++) {
                CompoundTag conditionTag = triggersTag.getCompound(i);
                rule.triggerConditions.add(TriggerCondition.fromNBT(conditionTag));
            }
            
            return rule;
        }
    }

    // 运镜路线类
    public static class MovementRoute {
        private String routeName;
        private PathType pathType;
        private Vec3 startPosition;
        private Vec3 endPosition;
        private Vec3 controlPosition; // 用于贝塞尔曲线
        private Vec3 targetPoint; // 用于轨道运动和滑动变焦
        private Vec3 backgroundPoint; // 用于高级滑动变焦
        private double duration; // 持续时间（秒）
        private float startFOV;
        private float endFOV;
        private float startYaw;
        private float startPitch;
        private float endYaw;
        private float endPitch;
        private double speed; // 移动速度
        private double radius; // 轨道半径
        private double height; // 轨道高度
        private double strength; // 滑动变焦强度
        private boolean isForward; // 运动方向
        private double heading; // 朝向偏移角度（度，用于轨道运动）

        public MovementRoute() {
            this.pathType = PathType.DIRECT;
            this.duration = 5.0;
            this.startFOV = 70;
            this.endFOV = 70;
            this.speed = 1.0;
            this.radius = 5.0;
            this.height = 2.0;
            this.strength = 1.0;
            this.isForward = true;
            this.heading = 0.0;
        }

        public double getHeading() {
            return heading;
        }

        public void setHeading(double heading) {
            this.heading = heading;
        }

        public String getRouteName() {
            return routeName;
        }

        public void setRouteName(String routeName) {
            this.routeName = routeName;
        }

        public PathType getPathType() {
            return pathType;
        }

        public void setPathType(PathType pathType) {
            this.pathType = pathType;
        }

        public Vec3 getStartPosition() {
            return startPosition;
        }

        public void setStartPosition(Vec3 startPosition) {
            this.startPosition = startPosition;
        }

        public Vec3 getEndPosition() {
            return endPosition;
        }

        public void setEndPosition(Vec3 endPosition) {
            this.endPosition = endPosition;
        }

        public Vec3 getControlPosition() {
            return controlPosition;
        }

        public void setControlPosition(Vec3 controlPosition) {
            this.controlPosition = controlPosition;
        }

        public Vec3 getTargetPoint() {
            return targetPoint;
        }

        public void setTargetPoint(Vec3 targetPoint) {
            this.targetPoint = targetPoint;
        }

        public Vec3 getBackgroundPoint() {
            return backgroundPoint;
        }

        public void setBackgroundPoint(Vec3 backgroundPoint) {
            this.backgroundPoint = backgroundPoint;
        }

        public double getDuration() {
            return duration;
        }

        public void setDuration(double duration) {
            this.duration = duration;
        }

        public float getStartFOV() {
            return startFOV;
        }

        public void setStartFOV(float startFOV) {
            this.startFOV = startFOV;
        }

        public float getEndFOV() {
            return endFOV;
        }

        public void setEndFOV(float endFOV) {
            this.endFOV = endFOV;
        }

        public float getStartYaw() {
            return startYaw;
        }

        public void setStartYaw(float startYaw) {
            this.startYaw = startYaw;
        }

        public float getStartPitch() {
            return startPitch;
        }

        public void setStartPitch(float startPitch) {
            this.startPitch = startPitch;
        }

        public float getEndYaw() {
            return endYaw;
        }

        public void setEndYaw(float endYaw) {
            this.endYaw = endYaw;
        }

        public float getEndPitch() {
            return endPitch;
        }

        public void setEndPitch(float endPitch) {
            this.endPitch = endPitch;
        }

        public double getSpeed() {
            return speed;
        }

        public void setSpeed(double speed) {
            this.speed = speed;
        }

        public double getRadius() {
            return radius;
        }

        public void setRadius(double radius) {
            this.radius = radius;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public double getStrength() {
            return strength;
        }

        public void setStrength(double strength) {
            this.strength = strength;
        }

        public boolean isForward() {
            return isForward;
        }

        public void setForward(boolean forward) {
            isForward = forward;
        }

        public CompoundTag toNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("routeName", routeName != null ? routeName : "");
            nbt.putString("pathType", pathType.name());
            nbt.putDouble("duration", duration);
            nbt.putFloat("startFOV", startFOV);
            nbt.putFloat("endFOV", endFOV);
            nbt.putFloat("startYaw", startYaw);
            nbt.putFloat("startPitch", startPitch);
            nbt.putFloat("endYaw", endYaw);
            nbt.putFloat("endPitch", endPitch);
            nbt.putDouble("speed", speed);
            nbt.putDouble("radius", radius);
            nbt.putDouble("height", height);
            nbt.putDouble("strength", strength);
            nbt.putBoolean("isForward", isForward);
            nbt.putDouble("heading", heading);
            
            if (startPosition != null) {
                ListTag startPosTag = new ListTag();
                startPosTag.add(DoubleTag.valueOf(startPosition.x));
                startPosTag.add(DoubleTag.valueOf(startPosition.y));
                startPosTag.add(DoubleTag.valueOf(startPosition.z));
                nbt.put("startPosition", startPosTag);
            }
            
            if (endPosition != null) {
                ListTag endPosTag = new ListTag();
                endPosTag.add(DoubleTag.valueOf(endPosition.x));
                endPosTag.add(DoubleTag.valueOf(endPosition.y));
                endPosTag.add(DoubleTag.valueOf(endPosition.z));
                nbt.put("endPosition", endPosTag);
            }
            
            if (controlPosition != null) {
                ListTag controlPosTag = new ListTag();
                controlPosTag.add(DoubleTag.valueOf(controlPosition.x));
                controlPosTag.add(DoubleTag.valueOf(controlPosition.y));
                controlPosTag.add(DoubleTag.valueOf(controlPosition.z));
                nbt.put("controlPosition", controlPosTag);
            }
            
            if (targetPoint != null) {
                ListTag targetPosTag = new ListTag();
                targetPosTag.add(DoubleTag.valueOf(targetPoint.x));
                targetPosTag.add(DoubleTag.valueOf(targetPoint.y));
                targetPosTag.add(DoubleTag.valueOf(targetPoint.z));
                nbt.put("targetPoint", targetPosTag);
            }
            
            if (backgroundPoint != null) {
                ListTag backgroundPosTag = new ListTag();
                backgroundPosTag.add(DoubleTag.valueOf(backgroundPoint.x));
                backgroundPosTag.add(DoubleTag.valueOf(backgroundPoint.y));
                backgroundPosTag.add(DoubleTag.valueOf(backgroundPoint.z));
                nbt.put("backgroundPoint", backgroundPosTag);
            }
            
            return nbt;
        }

        public static MovementRoute fromNBT(CompoundTag nbt) {
            MovementRoute route = new MovementRoute();
            route.routeName = nbt.getString("routeName");
            route.pathType = PathType.valueOf(nbt.getString("pathType"));
            route.duration = nbt.getDouble("duration");
            route.startFOV = nbt.getFloat("startFOV");
            route.endFOV = nbt.getFloat("endFOV");
            route.startYaw = nbt.getFloat("startYaw");
            route.startPitch = nbt.getFloat("startPitch");
            route.endYaw = nbt.getFloat("endYaw");
            route.endPitch = nbt.getFloat("endPitch");
            route.speed = nbt.getDouble("speed");
            route.radius = nbt.getDouble("radius");
            route.height = nbt.getDouble("height");
            route.strength = nbt.getDouble("strength");
            route.isForward = nbt.getBoolean("isForward");
            if (nbt.contains("heading")) {
                route.heading = nbt.getDouble("heading");
            }
            
            if (nbt.contains("startPosition")) {
                ListTag startPosTag = nbt.getList("startPosition", 6);
                route.startPosition = new Vec3(
                        startPosTag.getDouble(0),
                        startPosTag.getDouble(1),
                        startPosTag.getDouble(2)
                );
            }
            
            if (nbt.contains("endPosition")) {
                ListTag endPosTag = nbt.getList("endPosition", 6);
                route.endPosition = new Vec3(
                        endPosTag.getDouble(0),
                        endPosTag.getDouble(1),
                        endPosTag.getDouble(2)
                );
            }
            
            if (nbt.contains("controlPosition")) {
                ListTag controlPosTag = nbt.getList("controlPosition", 6);
                route.controlPosition = new Vec3(
                        controlPosTag.getDouble(0),
                        controlPosTag.getDouble(1),
                        controlPosTag.getDouble(2)
                );
            }
            
            if (nbt.contains("targetPoint")) {
                ListTag targetPosTag = nbt.getList("targetPoint", 6);
                route.targetPoint = new Vec3(
                        targetPosTag.getDouble(0),
                        targetPosTag.getDouble(1),
                        targetPosTag.getDouble(2)
                );
            }
            
            if (nbt.contains("backgroundPoint")) {
                ListTag backgroundPosTag = nbt.getList("backgroundPoint", 6);
                route.backgroundPoint = new Vec3(
                        backgroundPosTag.getDouble(0),
                        backgroundPosTag.getDouble(1),
                        backgroundPosTag.getDouble(2)
                );
            }
            
            return route;
        }
    }

    // 触发条件类
    public static class TriggerCondition {
        private TriggerType triggerType;
        private String parameter;
        private double value;

        public TriggerCondition() {
            this(TriggerType.DIMENSION_CHANGE, "", 0);
        }
        
        public TriggerCondition(TriggerType triggerType, String parameter, double value) {
            this.triggerType = triggerType;
            this.parameter = parameter;
            this.value = value;
        }

        public TriggerType getTriggerType() {
            return triggerType;
        }

        public void setTriggerType(TriggerType triggerType) {
            this.triggerType = triggerType;
        }

        public String getParameter() {
            return parameter;
        }

        public void setParameter(String parameter) {
            this.parameter = parameter;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }

        public CompoundTag toNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("triggerType", triggerType.name());
            nbt.putString("parameter", parameter != null ? parameter : "");
            nbt.putDouble("value", value);
            return nbt;
        }

        public static TriggerCondition fromNBT(CompoundTag nbt) {
            TriggerType type = TriggerType.valueOf(nbt.getString("triggerType"));
            String parameter = nbt.getString("parameter");
            double value = nbt.getDouble("value");
            return new TriggerCondition(type, parameter, value);
        }
    }

    // 路径类型枚举
    public enum PathType {
        DIRECT,          // 直接线性
        SMOOTH,          // 平滑曲线
        ORBIT,           // 轨道运动
        BEZIER,          // 贝塞尔曲线
        SPIRAL,          // 螺旋运动
        DOLLY_ZOOM,      // 滑动变焦
        STATIONARY_PAN   // 静态旋转
    }

    // 触发类型枚举
    public enum TriggerType {
        DIMENSION_CHANGE,    // 维度切换
        STRUCTURE_ENTER,     // 结构进入
        STRUCTURE_EXIT,      // 结构退出
        PLAYER_LOGIN,        // 玩家登录
        TIME_OF_DAY,         // 时间触发
        BLOCK_BREAK,         // 方块破坏
        ENTITY_KILL          // 实体击杀
    }
}