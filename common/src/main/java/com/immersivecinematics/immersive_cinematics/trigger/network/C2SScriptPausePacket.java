package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：脚本暂停/恢复状态同步。
 * <p>
 * 当客户端游戏暂停（pause_when_game_paused=true 且 MC 暂停）时发送，
 * 服务端停止处理该脚本的 EVENT keyframe，避免事件在暂停期间误触发。
 */
public class C2SScriptPausePacket extends BaseC2SMessage {

    private final String scriptId;
    private final boolean paused;

    public C2SScriptPausePacket(String scriptId, boolean paused) {
        this.scriptId = scriptId;
        this.paused = paused;
    }

    public C2SScriptPausePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.paused = buf.readBoolean();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.SCRIPT_PAUSE;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeBoolean(paused);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        ScriptEventManager.INSTANCE.handlePause(player, scriptId, paused);
    }

    public String getScriptId() { return scriptId; }
    public boolean isPaused() { return paused; }
}
