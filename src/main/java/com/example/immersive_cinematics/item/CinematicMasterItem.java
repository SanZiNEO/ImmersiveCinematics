package com.example.immersive_cinematics.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class CinematicMasterItem extends Item {
    public CinematicMasterItem(Properties properties) {
        super(properties
                .stacksTo(1)
                .fireResistant());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true; // 使物品有光泽效果
    }
}
