package com.example.immersive_cinematics.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CinematicMovementItem extends Item {
    private final String pathType;

    // 复用木棍的构造器逻辑（仅接收Properties）
    public CinematicMovementItem(Properties properties, String pathType) {
        super(properties);
        this.pathType = pathType;
    }

    // 1. 复用木棍：右键无任何交互（默认返回pass，和木棍一致）
    // 无需重写use()方法，直接继承Item的默认实现

    // 2. 你的需求：闪光效果（附魔光泽，非闪烁）
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    // 3. 简化tooltip（和木棍的hover文本逻辑一致，仅加路径说明）
    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, world, tooltip, flag); // 先执行木棍的默认tooltip逻辑
        String pathName = switch (pathType) {
            case "direct" -> "直线路径";
            case "smooth" -> "平滑路径";
            case "bezier" -> "贝塞尔曲线";
            case "dolly" -> "推拉镜头";
            case "orbit" -> "轨道环绕";
            case "spiral" -> "螺旋路径";
            case "pan" -> "摇摄路径";
            case "static" -> "静态镜头";
            default -> "未知路径";
        };
        tooltip.add(Component.literal("§a路径类型: §f" + pathName));
    }
}