package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 只显示成功结果、吞掉失败反馈的命令源。
 * <p>
 * 用途：脚本 EVENT 命令（ExecuteCommandAction / ScriptEventManager）——
 * 命令的<b>正常结果</b>（如 {@code /locate} 返回的坐标，走 sendSuccess）要发给玩家看，
 * 而<b>命令失败</b>的红字错误（sendFailure）不打扰玩家。
 * <p>
 * 实现：继承 {@link CommandSourceStack}（MC 无"只抑制失败"的现成选项，silent 是全吞），
 * 不设置 silent（sendSuccess/sendSystemMessage 正常放行），仅覆写 {@link #sendFailure(Component)} 为空。
 * 构造参数与 {@code Entity.createCommandSourceStack()} 一致（CommandSource = 玩家自己），
 * 权限按脚本命令惯例强制 4（op），无反射。
 */
public class SuccessOnlySource extends CommandSourceStack {

    public SuccessOnlySource(ServerPlayer player) {
        super(player, player.position(), player.getRotationVector(),
                player.serverLevel(), 4, player.getName().getString(),
                player.getDisplayName(), player.server, player);
    }

    @Override
    public void sendFailure(Component component) {
        // 吞掉命令失败消息：脚本自动执行的命令出错不打扰玩家（错误原因作者可从 ErrorLog 排查）
    }
}
