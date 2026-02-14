package com.example.immersive_cinematics.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class CinematicMasterItem extends Item {
    public CinematicMasterItem(Properties properties) {
        super(properties); // 复用木棍的Properties配置
    }

    // 可选：给总控制器也加闪光效果（和其他物品一致）
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    // 复用木棍：右键无任何交互（无需重写use()）
}
