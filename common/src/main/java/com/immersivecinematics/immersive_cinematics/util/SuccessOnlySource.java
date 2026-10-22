package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 只显示成功结果、吞掉失败反馈的命令源：用于脚本 EVENT 命令（ScriptEventManager，EVENT 轨）。
 *
 * 命令的正常结果（如 {@code /locate} 返回的坐标，走 sendSuccess）发给玩家；失败红字（sendFailure）丢弃。
 * 继承 {@link CommandSourceStack}：MC 没有「只抑制失败」的现成选项（silent 是全吞），
 * 故不设 silent（sendSuccess / sendSystemMessage 正常放行），只覆写 {@link #sendFailure(Component)} 为空。
 * 构造参数与 {@code Entity.createCommandSourceStack()} 一致（CommandSource = 玩家自己）；权限强制 4（op）。
 */
public class SuccessOnlySource extends CommandSourceStack {

    public SuccessOnlySource(ServerPlayer player) {
        super(player, player.position(), player.getRotationVector(),
                player.serverLevel(), 4, player.getName().getString(),
                player.getDisplayName(), player.server, player);
    }

    @Override
    public void sendFailure(Component component) {
        // 失败消息丢弃（作者排查走 ErrorLog）
    }
}
