package com.example.immersive_cinematics.test;

import com.example.immersive_cinematics.director.CameraScript;
import com.example.immersive_cinematics.director.CameraScriptStorage;
import com.example.immersive_cinematics.director.TriggerBinding;
import net.minecraft.world.phys.Vec3;

/**
 * 末地维度进入时的环绕摄像机脚本
 * 当玩家进入末地时，触发环绕摄像机运镜效果
 */
public class EndDimensionScript {

    public static void createEndDimensionScript() {
        // 初始化存储系统
        CameraScriptStorage storage = CameraScriptStorage.getInstance();
        storage.initialize();

        // 创建末地进入时的镜头脚本
        CameraScript script = new CameraScript("end_entry_cinematic", "末地维度进入时的环绕摄像机运镜");

        // 创建运镜规则 - 环绕末地传送门运动
        CameraScript.ShotRule orbitRule = new CameraScript.ShotRule("end_portal_orbit");
        CameraScript.MovementRoute orbitRoute = new CameraScript.MovementRoute();
        orbitRoute.setRouteName("portal_orbit");
        orbitRoute.setPathType(CameraScript.PathType.ORBIT);
        orbitRoute.setDuration(12.0); // 12秒环绕一周
        orbitRoute.setTargetPoint(new Vec3(0, 64, 0)); // 假设传送门在(0, 64, 0)
        orbitRoute.setRadius(8.0); // 环绕半径8格
        orbitRoute.setSpeed(1.5); // 运动速度
        orbitRoute.setHeight(3.0); // 轨道高度
        orbitRoute.setStartFOV(70); // 开始时FOV
        orbitRoute.setEndFOV(50); // 结束时FOV（缩放效果）
        orbitRule.addRoute(orbitRoute);

        // 添加触发条件 - 进入末地维度
        orbitRule.addTriggerCondition(new CameraScript.TriggerCondition(
                CameraScript.TriggerType.DIMENSION_CHANGE,
                "minecraft:the_end",
                0
        ));

        orbitRule.setLooping(false); // 不循环
        orbitRule.setDelay(2.0f); // 延迟2秒执行，给玩家缓冲时间
        script.addShotRule(orbitRule);

        // 创建第二个运镜规则 - 向上拉升镜头
        CameraScript.ShotRule liftRule = new CameraScript.ShotRule("sky_view");
        CameraScript.MovementRoute liftRoute = new CameraScript.MovementRoute();
        liftRoute.setRouteName("sky_lift");
        liftRoute.setPathType(CameraScript.PathType.SMOOTH); // 平滑曲线运动
        liftRoute.setDuration(8.0); // 8秒拉升
        liftRoute.setStartPosition(new Vec3(0, 67, 8)); // 开始位置在传送门上方右侧
        liftRoute.setEndPosition(new Vec3(0, 80, 0)); // 结束位置在传送门正上方16格
        liftRoute.setControlPosition(new Vec3(5, 75, 5)); // 控制点，创建弧形轨迹
        liftRoute.setStartYaw(0); // 开始偏航角
        liftRoute.setStartPitch(-45); // 开始俯仰角（向下看）
        liftRoute.setEndYaw(0); // 结束偏航角
        liftRoute.setEndPitch(-90); // 结束俯仰角（向上看）
        liftRoute.setStartFOV(50); // 开始FOV
        liftRoute.setEndFOV(80); // 结束FOV（广角效果）
        liftRule.addRoute(liftRoute);

        liftRule.setLooping(false);
        liftRule.setDelay(0.5f); // 第一个规则结束后立即执行
        script.addShotRule(liftRule);

        // 保存镜头脚本
        storage.saveScript("end_entry_cinematic", script);

        // 创建并保存触发绑定
        TriggerBinding binding = new TriggerBinding(
                "end_dimension_entry",
                "end_entry_cinematic",
                CameraScript.TriggerType.DIMENSION_CHANGE,
                40 // 延迟40刻（2秒）执行
        );
        storage.addBinding(binding);

        System.out.println("末地维度环绕摄像机脚本创建成功！");
        System.out.println("脚本名称: end_entry_cinematic");
        System.out.println("触发条件: 进入末地维度 (minecraft:the_end)");
        System.out.println("包含运镜规则:");
        System.out.println("1. end_portal_orbit: 环绕传送门运动");
        System.out.println("2. sky_view: 向上拉升镜头");
        System.out.println("脚本已保存到配置文件中");
    }

    public static void testEndScript() {
        // 测试创建脚本
        createEndDimensionScript();

        // 验证脚本是否成功加载
        CameraScriptStorage storage = CameraScriptStorage.getInstance();
        CameraScript loadedScript = storage.getScript("end_entry_cinematic");

        if (loadedScript != null) {
            System.out.println("\n脚本验证成功！");
            System.out.println("脚本名称: " + loadedScript.getName());
            System.out.println("描述: " + loadedScript.getDescription());
            System.out.println("运镜规则数量: " + loadedScript.getShotRuleCount());

            for (CameraScript.ShotRule rule : loadedScript.getShotRules()) {
                System.out.printf("\n运镜规则: %s (延迟: %.1f秒, 循环: %s)\n",
                        rule.getRuleName(), rule.getDelay(), rule.isLooping());
                System.out.println("运镜路线数量: " + rule.getRoutes().size());

                for (CameraScript.MovementRoute route : rule.getRoutes()) {
                    System.out.printf("  路线: %s (%s, %.1f秒)\n",
                            route.getRouteName(), route.getPathType().name(), route.getDuration());
                }

                System.out.println("触发条件数量: " + rule.getTriggerConditions().size());
                for (CameraScript.TriggerCondition condition : rule.getTriggerConditions()) {
                    System.out.printf("  触发条件: %s, 参数: %s\n",
                            condition.getTriggerType(), condition.getParameter());
                }
            }
        } else {
            System.out.println("\n脚本加载失败！");
        }
    }

    public static void main(String[] args) {
        testEndScript();
    }
}