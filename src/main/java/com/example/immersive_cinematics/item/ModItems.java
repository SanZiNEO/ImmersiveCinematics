package com.example.immersive_cinematics.item;

import com.example.immersive_cinematics.ImmersiveCinematics;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    // 物品注册器
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ImmersiveCinematics.MODID);

    // 运镜路线物品
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_ORBIT = ITEMS.register("cinematic_movement_orbit", () -> new CinematicMovementItem(new Item.Properties(), "orbit"));
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_DIRECT = ITEMS.register("cinematic_movement_direct", () -> new CinematicMovementItem(new Item.Properties(), "direct"));
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_SMOOTH = ITEMS.register("cinematic_movement_smooth", () -> new CinematicMovementItem(new Item.Properties(), "smooth"));
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_BEZIER = ITEMS.register("cinematic_movement_bezier", () -> new CinematicMovementItem(new Item.Properties(), "bezier"));
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_DOLLY = ITEMS.register("cinematic_movement_dolly", () -> new CinematicMovementItem(new Item.Properties(), "dolly"));
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_SPIRAL = ITEMS.register("cinematic_movement_spiral", () -> new CinematicMovementItem(new Item.Properties(), "spiral"));
    public static final RegistryObject<Item> CINEMATIC_STATIC_CAMERA = ITEMS.register("cinematic_static_camera", () -> new CinematicMovementItem(new Item.Properties(), "static"));
    public static final RegistryObject<Item> CINEMATIC_MOVEMENT_PAN = ITEMS.register("cinematic_movement_pan", () -> new CinematicMovementItem(new Item.Properties(), "pan"));

    // 运镜总控制器
    public static final RegistryObject<Item> CINEMATIC_MASTER = ITEMS.register("cinematic_master", () -> new CinematicMasterItem(new Item.Properties()));
}
