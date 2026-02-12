package com.example.immersive_cinematics.item;

import com.example.immersive_cinematics.ImmersiveCinematics;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    // 延迟注册器
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ImmersiveCinematics.MODID);
    
    // 模组专属创造分类页面
    public static final RegistryObject<CreativeModeTab> IMMERSIVE_CINEMATICS_TAB = CREATIVE_MODE_TABS.register("immersive_cinematics", () -> 
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.immersive_cinematics.immersive_cinematics"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> new ItemStack(ModItems.CINEMATIC_MASTER.get()))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.CINEMATIC_MASTER.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_ORBIT.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_DIRECT.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_SMOOTH.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_BEZIER.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_DOLLY.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_SPIRAL.get());
                output.accept(ModItems.CINEMATIC_STATIC_CAMERA.get());
                output.accept(ModItems.CINEMATIC_MOVEMENT_PAN.get());
            }).build());
}
