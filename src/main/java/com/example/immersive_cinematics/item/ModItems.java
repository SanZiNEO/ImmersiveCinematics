package com.example.immersive_cinematics.item;

import com.example.immersive_cinematics.ImmersiveCinematics;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    // 物品注册器
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ImmersiveCinematics.MODID);

    // ========== 复用木棍逻辑的核心配置 ==========
    // 通用属性模板（和木棍一致+你的定制）
    private static Item.Properties getCinematicItemProperties() {
        return new Item.Properties()
            .stacksTo(1)          // 你的需求：不可堆叠（木棍默认64，这里改1）
            .fireResistant()      // 你的需求：防火
            .setNoRepair();       // 复用木棍逻辑：无耐久/不可破坏（替代错误的durability(0)）
    }

    // 1. 总控制器物品（复用木棍逻辑）
    public static final RegistryObject<Item> CINEMATIC_MASTER = ITEMS.register(
        "cinematic_master",
        () -> new CinematicMasterItem(getCinematicItemProperties())
    );

    // 2. 8种路径物品（复用木棍逻辑）
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_DIRECT = ITEMS.register(
        "cinematic_movement_direct",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "direct")
    );
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_SMOOTH = ITEMS.register(
        "cinematic_movement_smooth",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "smooth")
    );
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_BEZIER = ITEMS.register(
        "cinematic_movement_bezier",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "bezier")
    );
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_DOLLY = ITEMS.register(
        "cinematic_movement_dolly",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "dolly")
    );
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_ORBIT = ITEMS.register(
        "cinematic_movement_orbit",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "orbit")
    );
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_SPIRAL = ITEMS.register(
        "cinematic_movement_spiral",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "spiral")
    );
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_PAN = ITEMS.register(
        "cinematic_movement_pan",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "pan")
    );
    public static final RegistryObject<Item> CINEMATIC_STATIC_CAMERA = ITEMS.register(
        "cinematic_static_camera",
        () -> new CinematicMovementItem(getCinematicItemProperties(), "static")
    );
}
