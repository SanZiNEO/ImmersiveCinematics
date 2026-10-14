package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：规则动作 {@code emit_command} 携带的命令（0.3.6 C6-3）。
 * <p>
 * 命令执行口径与 EVENT 轨一致（{@code &&} 拆分 + 成功结果回执，见
 * {@code ScriptEventManager#executeCommand}）；<b>权限 = 仅当发送者是该脚本当前播放实例的观看者</b>
 * （{@link ScriptEventManager#handleRuleCommand} 按 {@code (scriptId, instanceId)} 查观看者名单）——
 * 命令注入面因此收在「正在看这条脚本的玩家」之内，非观看者 / 无该实例一律丢弃。
 * <p>
 * {@code instanceId} 为 {@link S2CPlayScriptPacket#getInstanceId()} 的原样回传（§3.7）；
 * 无实例 id 的来源（本地播放）回传空串。
 */
public class C2SRuleCommandPacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id（§3.7）；空串=无实例 id 的来源 */
    private final String instanceId;
    /** 命令原文（不含前导 {@code /}；多条用 {@code &&} 分隔） */
    private final String command;

    public C2SRuleCommandPacket(String scriptId, String instanceId, String command) {
        this.scriptId = scriptId == null ? "" : scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.command = command == null ? "" : command;
    }

    public C2SRuleCommandPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.command = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeUtf(command);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.handleRuleCommand(player, scriptId, instanceId, command);
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public String getCommand() { return command; }
}
