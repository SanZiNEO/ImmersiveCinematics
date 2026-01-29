package com.example.immersive_cinematics.test;

import com.example.immersive_cinematics.director.CameraScript;
import com.example.immersive_cinematics.director.CameraScriptStorage;
import com.example.immersive_cinematics.director.TriggerBinding;
import net.minecraft.world.phys.Vec3;

/**
 * 镜头脚本系统测试类
 * 用于演示如何创建、保存和加载镜头脚本
 */
public class ScriptTest {

    public static void testScriptCreation() {
        // 初始化存储系统
        CameraScriptStorage storage = CameraScriptStorage.getInstance();
        storage.initialize();

        // 创建一个新镜头脚本
        CameraScript script = new CameraScript("test_script", "这是一个测试镜头脚本，包含移动、等待和文字显示");

        // 1. 创建第一个运镜规则 - 贝塞尔曲线运动
        CameraScript.ShotRule mainRule = new CameraScript.ShotRule("main_movement");
        CameraScript.MovementRoute bezierRoute = new CameraScript.MovementRoute();
        bezierRoute.setRouteName("bezier_move");
        bezierRoute.setPathType(CameraScript.PathType.BEZIER);
        bezierRoute.setDuration(10.0);
        bezierRoute.setStartPosition(new Vec3(0, 10, 0));
        bezierRoute.setControlPosition(new Vec3(5, 15, 5));
        bezierRoute.setEndPosition(new Vec3(10, 10, 10));
        bezierRoute.setStartYaw(0);
        bezierRoute.setStartPitch(0);
        bezierRoute.setEndYaw(90);
        bezierRoute.setEndPitch(0);
        bezierRoute.setStartFOV(70);
        bezierRoute.setEndFOV(40);
        mainRule.addRoute(bezierRoute);

        // 添加触发条件
        mainRule.addTriggerCondition(new CameraScript.TriggerCondition(
                CameraScript.TriggerType.DIMENSION_CHANGE,
                "minecraft:the_nether",
                0
        ));
        mainRule.setLooping(false);
        mainRule.setDelay(1.0f);

        // 添加到脚本
        script.addShotRule(mainRule);

        // 2. 创建第二个运镜规则 - 轨道运动
        CameraScript.ShotRule orbitRule = new CameraScript.ShotRule("orbit_view");
        CameraScript.MovementRoute orbitRoute = new CameraScript.MovementRoute();
        orbitRoute.setRouteName("orbit_target");
        orbitRoute.setPathType(CameraScript.PathType.ORBIT);
        orbitRoute.setDuration(8.0);
        orbitRoute.setTargetPoint(new Vec3(10, 10, 10));
        orbitRoute.setRadius(5.0);
        orbitRoute.setSpeed(1.0);
        orbitRoute.setHeight(2.0);
        orbitRoute.setStartFOV(60);
        orbitRoute.setEndFOV(60);
        orbitRule.addRoute(orbitRoute);

        // 设置循环播放
        orbitRule.setLooping(true);
        orbitRule.setDelay(0.5f);
        script.addShotRule(orbitRule);

        // 保存镜头脚本
        storage.saveScript("test_script", script);

        // 创建并保存触发绑定
        TriggerBinding binding = new TriggerBinding(
                "nether_entry_trigger",
                "test_script",
                CameraScript.TriggerType.DIMENSION_CHANGE,
                20
        );
        storage.addBinding(binding);

        // 测试加载镜头脚本
        CameraScript loadedScript = storage.getScript("test_script");
        if (loadedScript != null) {
            System.out.println("Camera script loaded successfully!");
            System.out.println("Script name: " + loadedScript.getName());
            System.out.println("Description: " + loadedScript.getDescription());
            System.out.println("Shot rules count: " + loadedScript.getShotRuleCount());

            // 打印每个运镜规则的信息
            for (int i = 0; i < loadedScript.getShotRules().size(); i++) {
                CameraScript.ShotRule rule = loadedScript.getShotRules().get(i);
                System.out.printf("\nRule %d: %s (delay: %.1f sec, looping: %s)%n",
                        i + 1,
                        rule.getRuleName(),
                        rule.getDelay(),
                        rule.isLooping());
                System.out.println("Routes count: " + rule.getRoutes().size());

                for (int j = 0; j < rule.getRoutes().size(); j++) {
                    CameraScript.MovementRoute route = rule.getRoutes().get(j);
                    System.out.printf("  Route %d: %s (%s, %.1f sec)%n",
                            j + 1,
                            route.getRouteName(),
                            route.getPathType().name(),
                            route.getDuration());
                }
            }
        }

        // 测试获取所有脚本
        System.out.println("\nAll camera scripts in storage:");
        for (String scriptName : storage.getCameraScripts().keySet()) {
            System.out.println("- " + scriptName);
        }

        // 测试获取所有触发绑定
        System.out.println("\nAll trigger bindings:");
        for (TriggerBinding b : storage.getBindings()) {
            System.out.println("- " + b);
        }

        System.out.println("\nCamera script creation and storage test completed!");
    }

    public static void testHotReload() {
        CameraScriptStorage storage = CameraScriptStorage.getInstance();
        System.out.println("\nTesting hot reload...");
        storage.hotReload();

        System.out.println("Camera scripts after hot reload: " + storage.getCameraScripts().size());
        System.out.println("Bindings after hot reload: " + storage.getBindings().size());
    }

    public static void main(String[] args) {
        testScriptCreation();
        testHotReload();
    }
}