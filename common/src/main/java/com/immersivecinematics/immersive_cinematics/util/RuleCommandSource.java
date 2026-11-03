package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 规则动作 {@code emit_command} 的受限命令源：保留玩家身份与成功结果回执，权限由服务端配置决定。
 *
 * <p>与 EVENT 轨的 {@link SuccessOnlySource}（权限恒 4）分开：EVENT 命令由服务端事件时间线触发，
 * 脚本属于服务端信任域；规则命令随客户端规则请求而来，权限上限必须由服务端配置控制
 * （{@code Config.ruleCommandPermissionLevel}，缺省 2），脚本与客户端都不能提高它。</p>
 *
 * <p>失败反馈静默：失败原因由 {@code RuleCommandAudit} 走 {@link ErrorLog} 限频记录，
 * 不把服务端命令错误刷给玩家（与 EVENT 轨同一口径）。</p>
 */
public class RuleCommandSource extends CommandSourceStack {

    /**
     * @param player          命令执行身份（权限主体）
     * @param permissionLevel 服务端配置的权限上限（不读玩家自身 op 等级）
     */
    public RuleCommandSource(ServerPlayer player, int permissionLevel) {
        super(player, player.position(), player.getRotationVector(),
                player.serverLevel(), permissionLevel, player.getName().getString(),
                player.getDisplayName(), player.server, player);
    }

    @Override
    public void sendFailure(Component component) {
        // 失败消息丢弃：失败原因进 RuleCommandAudit 的限频日志
    }
}
