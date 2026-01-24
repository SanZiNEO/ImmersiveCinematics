package com.example.immersive_cinematics.test;

import net.minecraft.client.player.LocalPlayer;
import java.lang.reflect.Constructor;

public class LocalPlayerTest {
    public static void main(String[] args) {
        try {
            // 反射获取 LocalPlayer 类
            Class<LocalPlayer> clazz = LocalPlayer.class;
            
            // 获取所有构造函数
            System.out.println("=== LocalPlayer 构造函数 ===");
            Constructor<?>[] constructors = clazz.getConstructors();
            for (int i = 0; i < constructors.length; i++) {
                System.out.println("\n构造函数 " + (i + 1) + ":");
                System.out.println("  参数类型:");
                
                Class<?>[] paramTypes = constructors[i].getParameterTypes();
                for (Class<?> paramType : paramTypes) {
                    System.out.println("    - " + paramType.getName());
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}